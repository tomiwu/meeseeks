package meeseeks

import meeseeks.fake.FakeClock
import meeseeks.protocol.*

/** Tests for the A2A-like protocol and per-process token system (issue #20).
  *
  * Covers: token issue and expiry, verifyMessage gate (FR19, FR20).
  */
class ProtocolSuite extends munit.FunSuite:

  test("valid token is accepted by the token store") {
    val clock = FakeClock(1_000L)
    val store = TokenStore.inMemory(clock)
    val token = store.issue("proc-1", ttlMillis = 60_000L)
    assertEquals(store.verify(token.id), Right(token))
  }

  test("expired token is refused with TokenError.Expired") {
    val clock = FakeClock(1_000L)
    val store = TokenStore.inMemory(clock)
    val token = store.issue("proc-1", ttlMillis = 100L)
    clock.advance(200L)
    assertEquals(store.verify(token.id), Left(TokenError.Expired))
  }

  test("unknown token is refused with TokenError.NotFound") {
    val store = TokenStore.inMemory()
    assertEquals(store.verify("no-such-token"), Left(TokenError.NotFound))
  }

  test("verifyMessage accepts a message with a valid token") {
    val clock = FakeClock(1_000L)
    val store = TokenStore.inMemory(clock)
    val token = store.issue("proc-1", ttlMillis = 60_000L)
    val envelope = A2AEnvelope(
      taskId = "task-1",
      sessionId = "session-1",
      message = A2AMessage("user", List(TextPart("run sbt test"))),
      tokenId = token.id
    )
    assertEquals(verifyMessage(envelope, store), Right(token))
  }

  test("verifyMessage refuses a message with an expired token") {
    val clock = FakeClock(1_000L)
    val store = TokenStore.inMemory(clock)
    val token = store.issue("proc-1", ttlMillis = 100L)
    clock.advance(200L)
    val envelope = A2AEnvelope(
      taskId = "task-2",
      sessionId = "session-1",
      message = A2AMessage("user", List(TextPart("run sbt test"))),
      tokenId = token.id
    )
    assertEquals(verifyMessage(envelope, store), Left(TokenError.Expired))
  }

  test("verifyMessage refuses a message with a wrong token") {
    val store = TokenStore.inMemory()
    val envelope = A2AEnvelope(
      taskId = "task-3",
      sessionId = "session-1",
      message = A2AMessage("user", List(TextPart("run sbt test"))),
      tokenId = "wrong-token-id"
    )
    assertEquals(verifyMessage(envelope, store), Left(TokenError.NotFound))
  }

end ProtocolSuite
