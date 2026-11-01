package meeseeks

import kyo.*
import meeseeks.fake.*
import meeseeks.port.*

/** Tests for Gateway: task parsing, refusal, and sender filtering. */
class GatewaySuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private val ownerId = "tomasz"

  private def run[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  test("valid build task is accepted") {
    val msg = ChatMessage(ownerId, "build: implement feature X\ncheck: sbt test")
    val chat = FakeChat.withMessages(msg)
    val gw = Gateway(ownerId, chat)
    val result = run(gw.readTask())
    result.fold(
      v => assertEquals(v, Some(Task("implement feature X", TaskType.Build, "sbt test"))),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(chat.sent.toList, Nil)
  }

  test("valid research task is accepted") {
    val msg = ChatMessage(ownerId, "research: Scala frameworks\nrubric: must cover Scala 3")
    val chat = FakeChat.withMessages(msg)
    val gw = Gateway(ownerId, chat)
    val result = run(gw.readTask())
    result.fold(
      v => assertEquals(v, Some(Task("Scala frameworks", TaskType.Research, "must cover Scala 3"))),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(chat.sent.toList, Nil)
  }

  test("task with no check is refused with exactly one message (AT3)") {
    val msg = ChatMessage(ownerId, "build: implement feature X")
    val chat = FakeChat.withMessages(msg)
    val gw = Gateway(ownerId, chat)
    val result = run(gw.readTask())
    result.fold(
      v => assertEquals(v, None),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(chat.sent.length, 1)
  }

  test("research task with no rubric is refused with exactly one message") {
    val msg = ChatMessage(ownerId, "research: latest tools")
    val chat = FakeChat.withMessages(msg)
    val gw = Gateway(ownerId, chat)
    val result = run(gw.readTask())
    result.fold(
      v => assertEquals(v, None),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(chat.sent.length, 1)
  }

  test("message from another user is ignored") {
    val msg = ChatMessage("other-user", "build: implement X\ncheck: sbt test")
    val chat = FakeChat.withMessages(msg)
    val gw = Gateway(ownerId, chat)
    val result = run(gw.readTask())
    result.fold(
      v => assertEquals(v, None),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(chat.sent.toList, Nil)
  }

  test("valid task sends no message to chat") {
    val msg = ChatMessage(ownerId, "build: fix the bug\ncheck: sbt test")
    val chat = FakeChat.withMessages(msg)
    val gw = Gateway(ownerId, chat)
    val _ = run(gw.readTask())
    assertEquals(chat.sent.toList, Nil)
  }

end GatewaySuite
