package meeseeks

import meeseeks.channel.ChatChannelKind
import meeseeks.channel.ChatConfig
import meeseeks.sandbox.Credential

/** Tests for chat channel configuration and token masking (issue #17 AC: tokens only from masked
  * fields).
  */
class ChatConfigSuite extends munit.FunSuite:

  test("ChatChannelKind has Telegram") {
    val kinds = ChatChannelKind.values.toSeq
    assert(kinds.contains(ChatChannelKind.Telegram))
  }

  test("ChatConfig holds Telegram kind with credential") {
    val cred = Credential("TELEGRAM_BOT_TOKEN", "123456:ABC-secret")
    val config = ChatConfig(ChatChannelKind.Telegram, Some(cred))
    assertEquals(config.kind, ChatChannelKind.Telegram)
    assertEquals(config.credential, Some(cred))
  }

  test("ChatConfig toString does not expose the bot token secret") {
    val secret = "123456:ABC-very-secret-token"
    val cred = Credential("TELEGRAM_BOT_TOKEN", secret)
    val config = ChatConfig(ChatChannelKind.Telegram, Some(cred))
    assert(!config.toString.contains(secret), s"secret found in: ${config.toString}")
  }

  test("Credential toString masks bot token") {
    val secret = "123456:ABC-very-secret-token"
    val cred = Credential("TELEGRAM_BOT_TOKEN", secret)
    assert(!cred.toString.contains(secret), s"secret found in: ${cred.toString}")
  }

  test("ChatConfig has no credential by default") {
    val config = ChatConfig(ChatChannelKind.Telegram)
    assertEquals(config.credential, None)
    assertEquals(config.tomaszChatId, None)
  }

  test("ChatConfig holds tomaszChatId") {
    val config = ChatConfig(ChatChannelKind.Telegram, tomaszChatId = Some(987654321L))
    assertEquals(config.tomaszChatId, Some(987654321L))
  }

end ChatConfigSuite
