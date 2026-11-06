package meeseeks

import kyo.*
import meeseeks.build.CheckError
import meeseeks.build.CheckResult
import meeseeks.build.PodmanCheckRunner
import meeseeks.fake.FakeSandbox
import meeseeks.port.SandboxError
import meeseeks.port.SandboxResult

/** Unit tests for PodmanCheckRunner. Uses FakeSandbox — no Podman binary required. */
class PodmanCheckRunnerSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private def run[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  test("PodmanCheckRunner returns Pass when sandbox exits with code 0") {
    val sandbox = FakeSandbox.withResults(SandboxResult(0, "all tests passed", ""))
    val runner = new PodmanCheckRunner(sandbox)
    val result = run(runner.run("sbt test"))
    result.fold(
      v => assertEquals(v, CheckResult.Pass),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("PodmanCheckRunner returns Fail with stderr when exit code is non-zero") {
    val sandbox = FakeSandbox.withResults(SandboxResult(1, "", "compilation error"))
    val runner = new PodmanCheckRunner(sandbox)
    val result = run(runner.run("sbt test"))
    result.fold(
      {
        case CheckResult.Fail(reason) =>
          assert(reason.contains("compilation error"), s"expected 'compilation error' in: $reason")
        case CheckResult.Pass => fail("expected Fail, got Pass")
      },
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test(
    "PodmanCheckRunner returns Fail with stdout when stderr is empty and exit code is non-zero"
  ) {
    val sandbox = FakeSandbox.withResults(SandboxResult(1, "test suite failed", ""))
    val runner = new PodmanCheckRunner(sandbox)
    val result = run(runner.run("sbt test"))
    result.fold(
      {
        case CheckResult.Fail(reason) =>
          assert(reason.contains("test suite failed"), s"expected stdout in reason: $reason")
        case CheckResult.Pass => fail("expected Fail, got Pass")
      },
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("PodmanCheckRunner maps SandboxError.Unavailable to CheckError.SandboxUnavailable") {
    val sandbox = FakeSandbox.withError(SandboxError.Unavailable("podman not found"))
    val runner = new PodmanCheckRunner(sandbox)
    val result = run(runner.run("sbt test"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case CheckError.SandboxUnavailable(msg) =>
          assert(msg.contains("podman not found"), s"expected message in: $msg")
        case other => fail(s"expected SandboxUnavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("PodmanCheckRunner maps SandboxError.Timeout to CheckError.ExecutionFailed") {
    val sandbox = FakeSandbox.withError(SandboxError.Timeout("timed out after 30s"))
    val runner = new PodmanCheckRunner(sandbox)
    val result = run(runner.run("sbt test"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case CheckError.ExecutionFailed(msg) =>
          assert(msg.contains("timeout"), s"expected 'timeout' in: $msg")
        case other => fail(s"expected ExecutionFailed, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("PodmanCheckRunner maps SandboxError.ContainerFailed to CheckError.ExecutionFailed") {
    val sandbox = FakeSandbox.withError(SandboxError.ContainerFailed("image not found"))
    val runner = new PodmanCheckRunner(sandbox)
    val result = run(runner.run("sbt test"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case CheckError.ExecutionFailed(msg) =>
          assert(msg.contains("image not found"), s"expected message in: $msg")
        case other => fail(s"expected ExecutionFailed, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("PodmanCheckRunner truncates long stderr to 500 characters") {
    val longError = "e" * 2000
    val sandbox = FakeSandbox.withResults(SandboxResult(1, "", longError))
    val runner = new PodmanCheckRunner(sandbox)
    val result = run(runner.run("cmd"))
    result.fold(
      {
        case CheckResult.Fail(reason) =>
          assert(reason.length <= 500, s"reason must be at most 500 chars, got ${reason.length}")
        case CheckResult.Pass => fail("expected Fail")
      },
      e => fail(s"$e"),
      t => fail(s"$t")
    )
  }

end PodmanCheckRunnerSuite
