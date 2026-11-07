# Running Meeseeks

This guide shows how to run the Meeseeks server on your host.

## Requirements

- JDK 25 (Temurin)
- sbt 2.0.12
- Podman (the server runs sandboxes and build checks in Podman)
- Docker Compose (`./setup.sh` uses it)
- A Telegram bot token from @BotFather and your numeric Telegram user ID

## One-step setup

1. Clone the repository.
2. Run `./setup.sh`.
   - It creates `.env` from `.env.example` if `.env` does not exist. It never overwrites `.env`.
   - It creates the journal directory `./data/journal`.
   - It makes mTLS certificates in `./certs` if they do not exist.
   - It starts the Compose services `journal-init` and `cert-helper`.
3. Open `.env`. Fill in the Telegram values. Never commit `.env`.
4. Load `.env` into your shell: `set -a; . ./.env; set +a`.
   The server reads real environment variables. It does not read the `.env` file.
5. Start the server on the host: `sbt "runtime/run"`.

The server is not in the Compose file. To check your tools and config without changes, run `./setup.sh --check`.

## Environment variables

This table has names only. Do not put real secrets in the repository.

| Name | Required | Meaning |
|---|---|---|
| `MEESEEKS_TELEGRAM_BOT_TOKEN` | Yes | Telegram bot token |
| `MEESEEKS_OWNER_ID` | Yes | Numeric Telegram user ID of the owner. The server ignores all other senders. |
| `MEESEEKS_TELEGRAM_CHAT_ID` | In `.env.example` | Telegram chat ID for results |
| `MEESEEKS_JOURNAL_PATH` | No | Journal directory. Default: `./data/journal` |
| `MEESEEKS_WRITER_ID` | No | Name stamped on each journal event. Default: `prod-1` |
| `MEESEEKS_BRAIN` | No | `subscription` (default), `apikey`, `local`, or `fake` |
| `MEESEEKS_SANDBOX_IMAGE` | No | Podman image for the brain and build checks. Default: `meeseeks/brain:latest` |
| `MEESEEKS_TASK_DIR` | No | Task directory for build checks. Default: `./task` |
| `MEESEEKS_BRAIN_SUBSCRIPTION_CREDENTIAL` | No | Credential for the subscription brain |
| `MEESEEKS_BRAIN_API_KEY` | For `apikey` | Anthropic API key |
| `MEESEEKS_BRAIN_API_MODEL` | No | Model for the `apikey` brain |
| `MEESEEKS_LOCAL_MODEL_HOST` | No | Host of the OpenAI-compatible endpoint for `local` |
| `MEESEEKS_LOCAL_MODEL_NAME` | No | Model name for `local` |
| `MEESEEKS_JEV_API_KEY` | No | Listed in `.env.example`. The server code does not read it. |

## Journal

The server writes its state to an H2 file journal in `./data/journal`. Git ignores `./data`.
Do not delete this directory if you want the server to recover.

## Brain

- The default brain is the Claude Subscription (`MEESEEKS_BRAIN=subscription`).
- The API key is optional. To use it, set `MEESEEKS_BRAIN=apikey` and `MEESEEKS_BRAIN_API_KEY`.
- For offline work, set `MEESEEKS_BRAIN=local` and point it to a local model endpoint.
- `MEESEEKS_BRAIN=fake` gives a fixed answer. Use it only for smoke tests.

Note: `.env.example` sets `MEESEEKS_BRAIN=fake`. Change it to use a real brain.

## Send tasks

Send messages to the bot in Telegram from the owner account. Every task needs a check.
A task with no check is refused.

Build task:

```text
build: add a function that reverses a string
check: sbt test
```

Research task:

```text
research: compare H2 and SQLite for a local journal
rubric: at least three claims, each with a working source URL
```

The bot replies `Task m-N accepted.` and later sends one result.

## Kill and resume

- Send `kill` in Telegram. The server gives no new permits.
- Send `resume` in Telegram. The circuit breaker closes and work can start again.

## Restart recovery

The server keeps state in the journal. After a stop or crash, start it again with
`sbt "runtime/run"` and the same `MEESEEKS_JOURNAL_PATH`. The actors recover their state
from the journal.

## Chat channel

Grok Bot has no channel API. Use Telegram.

## Tests

Run `sbt test`. The tests use fakes only. They do not call Telegram, Podman, or any model.

## Remote worker (issue #67)

A worker node runs on another machine. It joins the cluster with its own certificate, runs Podman there, and answers work requests. It stops itself after 60 s without server contact (FR8).

Set these variables, then run `sbt "runtime/runMain meeseeks.runWorker"`:

- `MEESEEKS_WORKER_HOST`, `MEESEEKS_WORKER_PORT`: address of the worker node.
- `MEESEEKS_SERVER_HOST`, `MEESEEKS_SERVER_PORT`: address of the server (the seed node).
- `MEESEEKS_TLS_KEYSTORE`, `MEESEEKS_TLS_TRUSTSTORE`: paths to the certificate files of the worker.
- `MEESEEKS_TLS_PASSWORD`: password of both files. This is a secret. The worker never logs it.
- `MEESEEKS_LEASE_SECONDS` (optional, default 60), `MEESEEKS_SANDBOX_IMAGE`, `MEESEEKS_TASK_DIR`.

The server side uses `ClusterWorker.live` as its `Worker`. The server must use the same TLS settings (`clusterTlsConfig`) and the actor system name `meeseeks`. Turning on the cluster mode of the server Main is not part of this issue.
