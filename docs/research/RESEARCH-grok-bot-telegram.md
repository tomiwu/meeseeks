# Research: Grok Bot Channel API and Telegram Fallback

Date: 2026-11-05. Status: draft.
Issue: #16.

## Purpose

This document researches how the Meeseeks server can send and receive messages through a dedicated Grok bot channel, and confirms the Telegram Bot API as a fallback. The goal is to satisfy FR1 (the server accepts tasks only from Tomasz's user ID on a chat channel) and Q14 (Grok bot first, Telegram fallback).

---

## 1. The Grok Bot product

### 1.1 What it is

Grok Bot (at x.ai) is a consumer AI assistant product. It runs in the x.ai desktop and mobile apps, in X Chat (the X app and x.com), and in Slack. A "Bot" in this product is a named AI agent that you interact with through a chat UI.

Grok Bot is **not** a programmable messaging platform in the same sense as the Telegram Bot API. There is no public endpoint where your server can long-poll for new messages from a user, and there is no documented callback that fires when Tomasz types a message in the Grok Bot chat.

### 1.2 Channels

| Channel | Availability |
|---|---|
| x.ai desktop and mobile app | Requires X Subscription Premium, Super, or Ultra (or legacy SuperGrok / Cursor plan) |
| X Chat (x.com and X app) | Rolling out; not available for company-managed email domains or team Cursor accounts |
| Slack | Personal bot: replies go to the x.ai app, not Slack. Shared bot: replies go to the Slack thread |

### 1.3 Automation webhooks (inbound trigger)

xAI provides an **automation webhook** that lets an external system trigger a Grok Bot routine via HTTP POST. This is an inbound trigger (external → Grok Bot), not a bidirectional channel.

**Endpoint:** Each saved trigger has a unique URL that you copy from the automation editor. The URL format is not published.

**Auth method:** Standard Webhooks (HMAC-SHA256).

Steps to authenticate an outbound request to the trigger:

1. Remove the `whsec_` prefix from the signing secret and base64-decode the remainder to get the HMAC key.
2. Build the signed content string: `{webhook-id}.{webhook-timestamp}.{raw-request-body}`.
3. Compute HMAC-SHA256 over that string with the key.
4. Base64-encode the digest and place it in the `webhook-signature` header as `v1,<digest>`.
5. The timestamp in `webhook-timestamp` must be within five minutes of Grok's current time.

Required request headers:

| Header | Value |
|---|---|
| `content-type` | `application/json` |
| `webhook-id` | Unique string per delivery |
| `webhook-timestamp` | Unix time in seconds (string) |
| `webhook-signature` | `v1,` + base64 HMAC-SHA256 digest |

**Body limit:** 1 MiB.

**Response codes:**

| Code | Meaning |
|---|---|
| 202 | Accepted; automation starts asynchronously |
| 400 | Missing or malformed header or timestamp |
| 401 | Signature rejected, stale timestamp, or trigger not accepting deliveries |
| 413 | Body exceeds 1 MiB |
| 429 | Too many requests (no published numeric limit) |
| 503 | Temporarily unavailable; retry with backoff |

**Critical gap:** A 202 only means the delivery was accepted. The page does not describe any reply callback, response body, or mechanism for the Grok Bot to push a result message back to the server or to Tomasz in the chat. The automation runs asynchronously, and the result is not returned in the HTTP response.

### 1.4 Test method

1. Create a Grok Bot account with a Premium or higher X Subscription.
2. Create a new Bot and add an automation with a webhook trigger.
3. Copy the trigger URL and signing secret from the editor.
4. Send an HTTP POST with correct HMAC headers and confirm a 202 response.
5. Verify that the bot runs the automation in the x.ai app UI.

**You cannot test a reply path programmatically.** There is no documented API to read the bot's response outside the x.ai app.

### 1.5 X Platform DM bot (alternative interpretation)

A second interpretation of "Grok bot first" is a bot account on X (Twitter) that uses the xAI inference API as its brain and communicates with Tomasz via X Direct Messages.

X API v2 supports DM conversations:

| Operation | Endpoint |
|---|---|
| Send DM | `POST /2/dm_conversations/{dm_conversation_id}/messages` (OAuth2, `dm.write` scope) |
| Receive DMs | Poll `GET /2/dm_events` or receive via X Activity API webhook |
| Create bot account | `POST /2/bots` (scopes: `dm.read`, `dm.write`, `tweet.read`, `users.read`, `media.write`) |

**Auth:** OAuth 2.0 PKCE with `dm.read` and `dm.write` scopes. The bot account has an "Automated by @owner" label.

**Limits:** A bot can DM only users who follow it, have open DMs, or accept a message request. Cold messages from non-Premium accounts are often silently dropped. X automation rules prohibit unsolicited, automated, bulk DMs.

**Practical constraint:** DM delivery to a specific user (Tomasz) requires that he follows the bot account or accepts a message request. This adds friction for initial setup.

---

## 2. Telegram Bot API

### 2.1 Auth method

Each bot has a token obtained from @BotFather. The token is the only credential. Its format is:

```
123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11
```

The token goes directly in the URL path of every request:

```
https://api.telegram.org/bot<token>/METHOD_NAME
```

There is no OAuth flow. Rotating the token (via @BotFather) immediately invalidates the old one.

### 2.2 Sending messages

Use `sendMessage` (or `sendPhoto`, `sendDocument`, etc.) with the target `chat_id` and the message text. All calls are HTTPS. Parameters can be JSON in the POST body (`application/json`), URL-encoded form, or a URL query string.

### 2.3 Receiving messages

Two methods, mutually exclusive:

| Method | How it works | When to use |
|---|---|---|
| Long polling (`getUpdates`) | Poll the API; returns up to 100 `Update` objects per call. Adjust `offset` to acknowledge received updates. No webhook needed. | Development, simple deployments, no public HTTPS endpoint |
| Webhook (`setWebhook`) | Telegram sends an HTTPS POST with a JSON `Update` to your server URL. Set `secret_token` for header verification (`X-Telegram-Bot-Api-Secret-Token`). | Production; faster delivery |

Supported webhook ports: 443, 80, 88, 8443. `getUpdates` does not work while a webhook is active; call `deleteWebhook` first to switch back to polling. Undelivered updates are kept for up to 24 hours.

### 2.4 Rate limits

No hard numeric limit is published for the hosted API. Community-documented soft limits (from the Bot FAQ):

| Scope | Limit |
|---|---|
| Single chat | ~1 message per second |
| Group chats | No more than 20 messages per minute per group |
| Bulk (broadcast) | ~30 messages per second |

Exceeding any limit returns HTTP 429. A local Bot API server (self-hosted) removes most limits and allows files up to 2000 MB.

### 2.5 Key endpoints

| Endpoint | Purpose |
|---|---|
| `getMe` | Confirm the bot token is valid; returns bot info |
| `getUpdates` | Long-poll for updates |
| `setWebhook` / `deleteWebhook` / `getWebhookInfo` | Manage webhook |
| `sendMessage` | Send a text message |
| `sendDocument` | Send a file |
| `editMessageText` | Edit a sent message |

### 2.6 Test method

1. Message @BotFather in Telegram and use `/newbot` to create a bot. Copy the token.
2. Start a conversation with the bot (search by username in the Telegram app).
3. Poll `GET https://api.telegram.org/bot<token>/getUpdates` to confirm you receive Tomasz's message.
4. Send `POST https://api.telegram.org/bot<token>/sendMessage` with `chat_id` and `text` to confirm delivery.
5. Confirm the message appears in the Telegram chat.
6. Optionally set a webhook and confirm Telegram posts updates to it.

---

## 3. xAI inference API (brain backend, not channel)

The xAI API at `api.x.ai/v1` is an OpenAI-compatible text generation API. It is not a messaging channel. It is relevant to Meeseeks as a **brain backend** (FR17, Q3), not as a chat channel.

**Base URL:** `https://api.x.ai/v1`

**Auth:** Bearer token in the `Authorization` header. The key starts with `xai-` and is obtained from the xAI Console.

**Key endpoint:** `POST /v1/chat/completions` (OpenAI-compatible).

**Rate limits (Tier 0, default):** Varies by model. Example: grok-4.7 at 150 RPS / 50M TPM. Tiers unlock automatically based on cumulative spend since 2026-01-01.

This API is a natural choice for the brain backend when Tomasz uses it via API key (Q3, NFR3). It is separate from any channel decision.

---

## 4. Findings and gaps

| Item | Grok Bot (x.ai product) | X DM bot | Telegram Bot API |
|---|---|---|---|
| Bidirectional programmatic channel | No | Yes (with caveats) | Yes |
| Auth method | HMAC-SHA256 (inbound trigger) | OAuth 2.0 PKCE | Bot token (URL path) |
| Receive messages from Tomasz | Not documented (no polling or callback) | X Activity API webhook or poll `GET /2/dm_events` | `getUpdates` (poll) or webhook |
| Send messages to Tomasz | Not documented | `POST /2/dm_conversations/{id}/messages` | `sendMessage` |
| Published rate limits | None (429 on excess) | None published | Soft: ~1 msg/s per chat, ~30/s total |
| Public HTTPS endpoint required | For outbound trigger only | For webhook (optional) | For webhook (optional) |
| Account required | X Subscription Premium+ | X developer account | Telegram account |
| Complexity of setup | High (automation editor, manual HMAC) | Medium (OAuth2, bot account) | Low (@BotFather token) |
| Bidirectionality confirmed | No | Yes | Yes |

**Critical finding:** The Grok Bot product (at x.ai) has no documented API for the server to receive messages typed by Tomasz in the Grok chat, and no documented callback for the bot to push results back to the server. It is a consumer product, not a programmable messaging channel.

**Gap for the codebase:** If "Grok bot first" means using the x.ai Grok Bot product as a channel, that path is blocked today. If it means an X DM bot backed by the xAI inference API, that path exists but requires an X developer account and OAuth2 setup, and has DM delivery constraints.

---

## 5. Recommendation

**Use the Telegram Bot API as the primary channel for v1.**

Reasons:

1. Telegram has a clean, well-documented bidirectional API (send and receive in a single bot token; no OAuth flow).
2. Setup is minimal: @BotFather issues the token in one step, long polling requires no public endpoint in development.
3. Rate limits are sufficient for a single-user, single-task server (FR1 limits senders to Tomasz only).
4. The Meeseeks gateway only needs to enforce Tomasz's `chat_id` (FR1); the Telegram API makes this straightforward.
5. The xAI inference API can still be used as a brain backend (Q3) regardless of channel choice.

**Grok Bot channel (deferred):**

Add Grok Bot or X DM as a second channel only after xAI publishes a stable bidirectional bot channel API. Watch the xAI changelog at `docs.x.ai/grok-bot` for updates. When it arrives, the gateway module is the only component that changes.

**Decision for ARCHITECTURE.md:** Update the "Grok bot channel API is OPEN" risk (PRD §11) to reflect that no bidirectional API exists today and Telegram is the practical first choice.

---

## 6. Sources

- xAI Grok Bot chat and collaboration: `https://docs.x.ai/grok-bot/chat-and-collaboration`
- xAI Grok Bot create and manage bots: `https://docs.x.ai/grok-bot/bots`
- xAI Grok automation webhooks: `https://docs.x.ai/grok/automations/webhooks`
- xAI rate limits: `https://docs.x.ai/developers/rate-limits`
- xAI API reference: `https://docs.x.ai/api`
- Telegram Bot API: `https://core.telegram.org/bots/api`
- X API v2 DM guide: `https://opentweet.io/how-to/send-x-dms-api`
- X Chat SDK adapter (Vercel): `https://vercel.com/changelog/chat-sdk-adds-x-adapter-support`
- Telegram rate limits (third-party summary): `https://www.activepieces.com/blog/telegram-bot-api-rate-limits-file-sizes-errors`
