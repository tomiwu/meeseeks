package meeseeks.channel

import meeseeks.sandbox.Credential

/** Configuration for a chat channel adapter.
  *
  * The credential holds the channel bot token. Its toString masks the secret value so the case
  * class auto-generated toString is safe to log.
  *
  * @param kind
  *   which channel adapter to use
  * @param credential
  *   bot token; never logged (Credential.toString shows only the name)
  * @param tomaszChatId
  *   Telegram chat ID used when sending result messages; required for the Telegram kind
  */
final case class ChatConfig(
    kind: ChatChannelKind,
    credential: Option[Credential] = None,
    tomaszChatId: Option[Long] = None
)
