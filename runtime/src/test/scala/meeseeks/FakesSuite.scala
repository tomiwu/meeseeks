package meeseeks

import kyo.*
import meeseeks.fake.*
import meeseeks.port.*
import scala.concurrent.duration.*

/** Tests for in-memory fakes. No network or real backends are used. */
class FakesSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private def run[E, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  // ---- Brain ----

  test("FakeBrain returns scripted value") {
    val brain = FakeBrain.withValues("hello")
    val result = run(brain.ask("prompt"))
    result.fold(
      v => assertEquals(v, "hello"),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeBrain returns scripted error") {
    val brain = FakeBrain.withError(BrainError.RateLimit("quota exceeded"))
    val result = run(brain.ask("prompt"))
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, BrainError.RateLimit("quota exceeded")),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeBrain returns multiple scripted values in order") {
    val brain = FakeBrain.withValues("first", "second")
    val r1 = run(brain.ask("a"))
    val r2 = run(brain.ask("b"))
    r1.fold(v => assertEquals(v, "first"), e => fail(s"$e"), t => fail(s"$t"))
    r2.fold(v => assertEquals(v, "second"), e => fail(s"$e"), t => fail(s"$t"))
  }

  test("FakeBrain fails when queue is empty") {
    val brain = FakeBrain.withValues()
    val result = run(brain.ask("prompt"))
    result.fold(
      _ => fail("expected failure"),
      e => e match
        case _: BrainError.Unavailable => ()
        case other => fail(s"wrong error type: $other"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeBrain delay script returns value after delay") {
    val brain = FakeBrain.scripted(Script.after(10.millis, "delayed"))
    val result = run(brain.ask("prompt"))
    result.fold(
      v => assertEquals(v, "delayed"),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  // ---- Sandbox ----

  test("FakeSandbox returns scripted result") {
    val expected = SandboxResult(0, "ok", "")
    val sandbox = FakeSandbox.withResults(expected)
    val result = run(sandbox.run("ls"))
    result.fold(
      v => assertEquals(v, expected),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeSandbox returns scripted error") {
    val sandbox = FakeSandbox.withError(SandboxError.Timeout("timed out"))
    val result = run(sandbox.run("slow-cmd"))
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, SandboxError.Timeout("timed out")),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeSandbox returns multiple scripted results in order") {
    val r1 = SandboxResult(0, "pass", "")
    val r2 = SandboxResult(1, "", "error")
    val sandbox = FakeSandbox.withResults(r1, r2)
    val res1 = run(sandbox.run("check"))
    val res2 = run(sandbox.run("check"))
    res1.fold(v => assertEquals(v, r1), e => fail(s"$e"), t => fail(s"$t"))
    res2.fold(v => assertEquals(v, r2), e => fail(s"$e"), t => fail(s"$t"))
  }

  test("FakeSandbox delay script returns result after delay") {
    val expected = SandboxResult(0, "ok", "")
    val sandbox = FakeSandbox.scripted(Script.after(10.millis, expected))
    val result = run(sandbox.run("cmd"))
    result.fold(
      v => assertEquals(v, expected),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeSandbox fails when queue is empty") {
    val sandbox = FakeSandbox.withResults()
    val result = run(sandbox.run("cmd"))
    result.fold(
      _ => fail("expected failure"),
      e => e match
        case _: SandboxError.Unavailable => ()
        case other => fail(s"wrong error type: $other"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  // ---- Chat ----

  test("FakeChat returns scripted message") {
    val msg = ChatMessage("tomasz", "build the thing")
    val chat = FakeChat.withMessages(msg)
    val result = run(chat.receive())
    result.fold(
      v => assertEquals(v, msg),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeChat returns scripted error on receive") {
    val chat = FakeChat.withError(ChatError.Disconnected("channel gone"))
    val result = run(chat.receive())
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, ChatError.Disconnected("channel gone")),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeChat collects sent messages") {
    val chat = FakeChat.withMessages()
    val r1 = run(chat.send("hello"))
    val r2 = run(chat.send("world"))
    r1.fold(_ => (), e => fail(s"$e"), t => fail(s"$t"))
    r2.fold(_ => (), e => fail(s"$e"), t => fail(s"$t"))
    assertEquals(chat.sent.toList, List("hello", "world"))
  }

  test("FakeChat returns multiple scripted messages in order") {
    val m1 = ChatMessage("tomasz", "first task")
    val m2 = ChatMessage("tomasz", "second task")
    val chat = FakeChat.withMessages(m1, m2)
    val r1 = run(chat.receive())
    val r2 = run(chat.receive())
    r1.fold(v => assertEquals(v, m1), e => fail(s"$e"), t => fail(s"$t"))
    r2.fold(v => assertEquals(v, m2), e => fail(s"$e"), t => fail(s"$t"))
  }

  test("FakeChat fails receive when queue is empty") {
    val chat = FakeChat.withMessages()
    val result = run(chat.receive())
    result.fold(
      _ => fail("expected failure"),
      e => e match
        case _: ChatError.Disconnected => ()
        case other => fail(s"wrong error type: $other"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  // ---- Worker ----

  test("FakeWorker returns scripted result") {
    val expected = WorkResult(0, "build passed")
    val worker = FakeWorker.withResults(expected)
    val result = run(worker.submit(WorkItem("build", "sbt test")))
    result.fold(
      v => assertEquals(v, expected),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeWorker returns scripted error") {
    val worker = FakeWorker.withError(WorkerError.Overloaded("too busy"))
    val result = run(worker.submit(WorkItem("build", "sbt test")))
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, WorkerError.Overloaded("too busy")),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeWorker returns multiple scripted results in order") {
    val r1 = WorkResult(0, "first pass")
    val r2 = WorkResult(0, "second pass")
    val worker = FakeWorker.withResults(r1, r2)
    val res1 = run(worker.submit(WorkItem("build", "sbt test")))
    val res2 = run(worker.submit(WorkItem("build", "sbt test")))
    res1.fold(v => assertEquals(v, r1), e => fail(s"$e"), t => fail(s"$t"))
    res2.fold(v => assertEquals(v, r2), e => fail(s"$e"), t => fail(s"$t"))
  }

  test("FakeWorker fails when queue is empty") {
    val worker = FakeWorker.withResults()
    val result = run(worker.submit(WorkItem("build", "sbt test")))
    result.fold(
      _ => fail("expected failure"),
      e => e match
        case _: WorkerError.Unreachable => ()
        case other => fail(s"wrong error type: $other"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  // ---- Oracle ----

  test("FakeOracle returns scripted Pass verdict") {
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val input = OracleInput("build the app", "all tests passed")
    val result = run(oracle.judge(input))
    result.fold(
      v => assertEquals(v, OracleVerdict.Pass),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeOracle returns scripted Fail verdict") {
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Fail("tests failed"))
    val input = OracleInput("build the app", "compilation error")
    val result = run(oracle.judge(input))
    result.fold(
      v => assertEquals(v, OracleVerdict.Fail("tests failed")),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeOracle returns scripted error") {
    val oracle = FakeOracle.withError(OracleError.Unavailable("oracle down"))
    val result = run(oracle.judge(OracleInput("goal", "output")))
    result.fold(
      _ => fail("expected failure"),
      e => assertEquals(e, OracleError.Unavailable("oracle down")),
      t => fail(s"unexpected panic: $t"),
    )
  }

  test("FakeOracle returns multiple scripted verdicts in order") {
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass, OracleVerdict.Fail("not done"))
    val input = OracleInput("goal", "output")
    val r1 = run(oracle.judge(input))
    val r2 = run(oracle.judge(input))
    r1.fold(v => assertEquals(v, OracleVerdict.Pass), e => fail(s"$e"), t => fail(s"$t"))
    r2.fold(
      v => assertEquals(v, OracleVerdict.Fail("not done")),
      e => fail(s"$e"),
      t => fail(s"$t"),
    )
  }

  test("FakeOracle fails when queue is empty") {
    val oracle = FakeOracle.withVerdicts()
    val result = run(oracle.judge(OracleInput("goal", "output")))
    result.fold(
      _ => fail("expected failure"),
      e => e match
        case _: OracleError.Unavailable => ()
        case other => fail(s"wrong error type: $other"),
      t => fail(s"unexpected panic: $t"),
    )
  }

end FakesSuite
