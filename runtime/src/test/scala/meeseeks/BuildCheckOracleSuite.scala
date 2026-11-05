package meeseeks

import kyo.*
import meeseeks.build.*
import meeseeks.fake.*
import meeseeks.port.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import java.nio.file.Files
import scala.concurrent.duration.*

/** Tests for AT4 and AT5: build check runner and test-writer guard.
  *
  * AT4: a fake brain makes a failing check pass; the task ends with one result message. AT5: a code
  * writer that changes a test file gets a fail. Self-review is structurally impossible:
  * BuildCheckOracle uses only CheckRunner, never Brain.
  */
class BuildCheckOracleSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private def runOracle(
      effect: OracleVerdict < (Sync & Abort[OracleError])
  ): Result[OracleError, OracleVerdict] =
    Sync.Unsafe.evalOrThrow(Abort.run[OracleError](effect))

  // AT4: check fails first, brain produces code, check passes on second iteration, one result sent.
  test("AT4: check passes after fail, one result message is sent") {
    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("code attempt 1", "code attempt 2")
    val checkRunner =
      FakeCheckRunner.withResults(CheckResult.Fail("tests do not pass yet"), CheckResult.Pass)
    val guard = new TestFileGuard(Set.empty)
    val oracle = new BuildCheckOracle(guard, checkRunner, "sbt test")
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("add feature X", TaskType.Build, "sbt test")
    val overseer = kit.spawn(Overseer("at4-os-1"), "at4-os-1")

    val _ = kit.spawn(
      Meeseeks(
        "at4-ms-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        permitRetryDelay = 50.millis
      ),
      "at4-ms-1"
    )

    val result = resultProbe.receiveMessage(10.seconds)
    assertEquals(result, "Task complete: code attempt 2")

    kit.shutdownTestKit()
  }

  // AT5: code writer changes a test file; oracle returns Fail without running the check.
  test("AT5: code writer changes test file, oracle returns Fail") {
    val dir = Files.createTempDirectory("at5-test-")
    val testFile = dir.resolve("ExampleTest.scala")
    val _ = Files.writeString(testFile, "// original test content")

    val guard = new TestFileGuard(Set(testFile))
    val checkRunner = FakeCheckRunner.withResults(CheckResult.Pass) // must not be called
    val oracle = new BuildCheckOracle(guard, checkRunner, "sbt test")

    // Simulate code writer modifying the test file.
    val _ = Files.writeString(testFile, "// modified by code writer")

    val result = runOracle(oracle.judge(OracleInput("add feature X", "brain output")))

    result.fold(
      {
        case OracleVerdict.Fail(reason) =>
          assert(reason.contains("test file"), s"expected 'test file' in reason, got: $reason")
        case other =>
          fail(s"expected OracleVerdict.Fail, got $other")
      },
      e => fail(s"unexpected oracle error: $e"),
      t => fail(s"unexpected panic: $t")
    )

    val _ = Files.deleteIfExists(testFile)
    val _ = Files.deleteIfExists(dir)
  }

  // TestFileGuard: unchanged file returns None.
  test("TestFileGuard: unchanged file returns None") {
    val dir = Files.createTempDirectory("guard-no-change-")
    val testFile = dir.resolve("TestA.scala")
    val _ = Files.writeString(testFile, "content")

    val guard = new TestFileGuard(Set(testFile))
    assertEquals(guard.detectChange(), None)

    val _ = Files.deleteIfExists(testFile)
    val _ = Files.deleteIfExists(dir)
  }

  // TestFileGuard: modified file returns Some.
  test("TestFileGuard: modified file is detected") {
    val dir = Files.createTempDirectory("guard-modified-")
    val testFile = dir.resolve("TestB.scala")
    val _ = Files.writeString(testFile, "original")

    val guard = new TestFileGuard(Set(testFile))
    val _ = Files.writeString(testFile, "modified")

    val result = guard.detectChange()
    assert(result.isDefined, "expected Some when file is modified")
    assert(result.get.contains("modified"), s"expected 'modified' in reason: ${result.get}")

    val _ = Files.deleteIfExists(testFile)
    val _ = Files.deleteIfExists(dir)
  }

  // TestFileGuard: absent file added returns Some.
  test("TestFileGuard: added file is detected") {
    val dir = Files.createTempDirectory("guard-added-")
    val testFile = dir.resolve("TestC.scala")
    // File does not exist at construction time.

    val guard = new TestFileGuard(Set(testFile))
    val _ = Files.writeString(testFile, "new content")

    val result = guard.detectChange()
    assert(result.isDefined, "expected Some when file is added")

    val _ = Files.deleteIfExists(testFile)
    val _ = Files.deleteIfExists(dir)
  }

  // TestFileGuard: existing file removed returns Some.
  test("TestFileGuard: removed file is detected") {
    val dir = Files.createTempDirectory("guard-removed-")
    val testFile = dir.resolve("TestD.scala")
    val _ = Files.writeString(testFile, "content to remove")

    val guard = new TestFileGuard(Set(testFile))
    val _ = Files.deleteIfExists(testFile)

    val result = guard.detectChange()
    assert(result.isDefined, "expected Some when file is removed")

    val _ = Files.deleteIfExists(dir)
  }

  // BuildCheckOracle: no test changes, check passes, oracle returns Pass.
  test("BuildCheckOracle: no test changes, check passes, returns Pass") {
    val guard = new TestFileGuard(Set.empty)
    val checkRunner = FakeCheckRunner.withResults(CheckResult.Pass)
    val oracle = new BuildCheckOracle(guard, checkRunner, "sbt test")

    val result = runOracle(oracle.judge(OracleInput("goal", "output")))
    result.fold(
      v => assertEquals(v, OracleVerdict.Pass),
      e => fail(s"unexpected oracle error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  // BuildCheckOracle: no test changes, check fails, oracle returns Fail with check reason.
  test("BuildCheckOracle: no test changes, check fails, returns Fail") {
    val guard = new TestFileGuard(Set.empty)
    val checkRunner = FakeCheckRunner.withResults(CheckResult.Fail("compilation error"))
    val oracle = new BuildCheckOracle(guard, checkRunner, "sbt test")

    val result = runOracle(oracle.judge(OracleInput("goal", "output")))
    result.fold(
      {
        case OracleVerdict.Fail(r) => assertEquals(r, "compilation error")
        case other                 => fail(s"expected Fail, got $other")
      },
      e => fail(s"unexpected oracle error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  // BuildCheckOracle: check runner error maps to OracleError.
  test("BuildCheckOracle: check runner error becomes OracleError.Unavailable") {
    val guard = new TestFileGuard(Set.empty)
    val checkRunner = FakeCheckRunner.withError(CheckError.SandboxUnavailable("container down"))
    val oracle = new BuildCheckOracle(guard, checkRunner, "sbt test")

    val result = runOracle(oracle.judge(OracleInput("goal", "output")))
    result.fold(
      v => fail(s"expected failure, got $v"),
      {
        case OracleError.Unavailable(_) => ()
        case other                      => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

end BuildCheckOracleSuite
