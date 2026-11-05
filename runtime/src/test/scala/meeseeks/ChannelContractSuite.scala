package meeseeks

import kyo.*
import meeseeks.channel.FallbackChat
import meeseeks.channel.TelegramChat
import meeseeks.channel.TelegramUpdate
import meeseeks.fake.FakeChat
import meeseeks.fake.FakeTelegramApi
import meeseeks.port.Chat
import meeseeks.port.ChatError
import meeseeks.port.ChatMessage

/** Contract tests for chat channel adapters (issue #17).
  *
  * AC1: TelegramChat passes the Chat contract against FakeTelegramApi (no real network).
  * AC2: FallbackChat switches to secondary on primary Disconnected.
  * AC3: tokens come only from masked Credential fields (see ChatConfigSuite).
  */
class ChannelContractSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private val tomaszId = "987654321"
  private val tomaszChatId = 987654321L

  private def run[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  /** Chat that always fails every operation with the given error. Used to test fallback. */
  private final class AlwaysFailChat(error: ChatError) extends Chat:
    def receive(): ChatMessage < (Sync & Abort[ChatError]) = Abort.fail(error)
    def send(text: String): Unit < (Sync & Abort[ChatError]) = Abort.fail(error)

  // ---- TelegramChat receive ----

  test("TelegramChat.receive returns message from scripted update") {
    val upd = TelegramUpdate(1L, tomaszId, tomaszChatId, "build: foo\ncheck: sbt test")
    val api = FakeTelegramApi.withBatches(List(upd))
    val chat = TelegramChat(api, tomaszChatId)
    val result = run(chat.receive())
    result.fold(
      v => assertEquals(v, ChatMessage(tomaszId, "build: foo\ncheck: sbt test")),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("TelegramChat.receive advances offset past the received update") {
    val upd1 = TelegramUpdate(10L, tomaszId, tomaszChatId, "first")
    val upd2 = TelegramUpdate(11L, tomaszId, tomaszChatId, "second")
    val api = FakeTelegramApi.withBatches(List(upd1), List(upd2))
    val chat = TelegramChat(api, tomaszChatId)
    val r1 = run(chat.receive())
    val r2 = run(chat.receive())
    r1.fold(v => assertEquals(v.text, "first"), e => fail(s"$e"), t => fail(s"$t"))
    r2.fold(v => assertEquals(v.text, "second"), e => fail(s"$e"), t => fail(s"$t"))
  }

  test("TelegramChat.receive buffers multiple updates from one batch") {
    val upd1 = TelegramUpdate(20L, tomaszId, tomaszChatId, "msg one")
    val upd2 = TelegramUpdate(21L, tomaszId, tomaszChatId, "msg two")
    val api = FakeTelegramApi.withBatches(List(upd1, upd2))
    val chat = TelegramChat(api, tomaszChatId)
    val r1 = run(chat.receive())
    val r2 = run(chat.receive())
    r1.fold(v => assertEquals(v.text, "msg one"), e => fail(s"$e"), t => fail(s"$t"))
    r2.fold(v => assertEquals(v.text, "msg two"), e => fail(s"$e"), t => fail(s"$t"))
  }

  test("TelegramChat.receive propagates Disconnected error") {
    val api = FakeTelegramApi.withError(ChatError.Disconnected("line dropped"))
    val chat = TelegramChat(api, tomaszChatId)
    val result = run(chat.receive())
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, ChatError.Disconnected("line dropped")),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("TelegramChat.receive propagates Unauthorized error") {
    val api = FakeTelegramApi.withError(ChatError.Unauthorized("bad token"))
    val chat = TelegramChat(api, tomaszChatId)
    val result = run(chat.receive())
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, ChatError.Unauthorized("bad token")),
      t => fail(s"unexpected panic: $t")
    )
  }

  // ---- TelegramChat send ----

  test("TelegramChat.send delivers text to tomaszChatId") {
    val api = FakeTelegramApi.withBatches()
    val chat = TelegramChat(api, tomaszChatId)
    val result = run(chat.send("task complete"))
    result.fold(
      _ => assertEquals(api.sentMessages.toList, List((tomaszChatId, "task complete"))),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("TelegramChat.send multiple messages in order") {
    val api = FakeTelegramApi.withBatches()
    val chat = TelegramChat(api, tomaszChatId)
    val _ = run(chat.send("first"))
    val _ = run(chat.send("second"))
    assertEquals(
      api.sentMessages.toList,
      List((tomaszChatId, "first"), (tomaszChatId, "second"))
    )
  }

  // ---- FallbackChat ----

  test("FallbackChat.receive returns primary message when primary works") {
    val primary = FakeChat.withMessages(ChatMessage(tomaszId, "from primary"))
    val secondary = FakeChat.withMessages(ChatMessage(tomaszId, "from secondary"))
    val chat = FallbackChat(primary, secondary)
    val result = run(chat.receive())
    result.fold(
      v => assertEquals(v, ChatMessage(tomaszId, "from primary")),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("FallbackChat.receive falls back to secondary on primary Disconnected") {
    val primary = AlwaysFailChat(ChatError.Disconnected("primary down"))
    val secondary = FakeChat.withMessages(ChatMessage(tomaszId, "from secondary"))
    val chat = FallbackChat(primary, secondary)
    val result = run(chat.receive())
    result.fold(
      v => assertEquals(v, ChatMessage(tomaszId, "from secondary")),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("FallbackChat.receive propagates Unauthorized without fallback") {
    val primary = AlwaysFailChat(ChatError.Unauthorized("bad token"))
    val secondary = FakeChat.withMessages(ChatMessage(tomaszId, "from secondary"))
    val chat = FallbackChat(primary, secondary)
    val result = run(chat.receive())
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, ChatError.Unauthorized("bad token")),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("FallbackChat.send falls back to secondary on primary Disconnected") {
    val primary = AlwaysFailChat(ChatError.Disconnected("primary down"))
    val secondary = FakeChat.withMessages()
    val chat = FallbackChat(primary, secondary)
    val result = run(chat.send("hello"))
    result.fold(
      _ => assertEquals(secondary.sent.toList, List("hello")),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("FallbackChat.send propagates Unauthorized without fallback") {
    val primary = AlwaysFailChat(ChatError.Unauthorized("bad token"))
    val secondary = FakeChat.withMessages()
    val chat = FallbackChat(primary, secondary)
    val result = run(chat.send("hello"))
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, ChatError.Unauthorized("bad token")),
      t => fail(s"unexpected panic: $t")
    )
  }

  // ---- Gateway integration with TelegramChat ----

  test("Gateway accepts task from TelegramChat backed by FakeTelegramApi") {
    val upd = TelegramUpdate(
      1L,
      tomaszId,
      tomaszChatId,
      "build: add login page\ncheck: sbt test"
    )
    val api = FakeTelegramApi.withBatches(List(upd))
    val chat = TelegramChat(api, tomaszChatId)
    val gw = Gateway(tomaszId, chat)
    val result = run(gw.readTask())
    result.fold(
      v => assertEquals(v, Some(Task("add login page", TaskType.Build, "sbt test"))),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("Gateway ignores message from non-owner via TelegramChat") {
    val upd = TelegramUpdate(1L, "other-user", 11111L, "build: X\ncheck: sbt test")
    val api = FakeTelegramApi.withBatches(List(upd))
    val chat = TelegramChat(api, tomaszChatId)
    val gw = Gateway(tomaszId, chat)
    val result = run(gw.readTask())
    result.fold(
      v => assertEquals(v, None),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(api.sentMessages.toList, Nil)
  }

end ChannelContractSuite
