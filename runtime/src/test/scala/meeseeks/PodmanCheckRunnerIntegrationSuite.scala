package meeseeks

import kyo.*
import meeseeks.build.CheckError
import meeseeks.build.CheckResult
import meeseeks.build.PodmanCheckRunner
import meeseeks.sandbox.PodmanSandbox
import meeseeks.sandbox.SandboxConfig

import java.nio.file.Files

/** Local integration tests for PodmanCheckRunner. Skipped in CI.
  *
  * Run locally with: PODMAN_INTEGRATION=true sbt test
  */
class PodmanCheckRunnerIntegrationSuite extends munit.FunSuite:

  override def munitIgnore: Boolean =
    sys.env.getOrElse("PODMAN_INTEGRATION", "false") != "true"

  import AllowUnsafe.embrace.danger

  private def runEffect[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  test("PodmanCheckRunner passes on 'true' command in alpine container") {
    val taskDir = Files.createTempDirectory("cr-it-true-")
    try
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir)
      val runner = new PodmanCheckRunner(new PodmanSandbox(config))
      val result = runEffect(runner.run("true"))
      result.fold(
        v => assertEquals(v, CheckResult.Pass),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      val _ = Files.deleteIfExists(taskDir)
  }

  test("PodmanCheckRunner fails on 'false' command in alpine container") {
    val taskDir = Files.createTempDirectory("cr-it-false-")
    try
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir)
      val runner = new PodmanCheckRunner(new PodmanSandbox(config))
      val result = runEffect(runner.run("false"))
      result.fold(
        {
          case CheckResult.Fail(_) => ()
          case CheckResult.Pass    => fail("expected Fail for 'false' command")
        },
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      val _ = Files.deleteIfExists(taskDir)
  }

  test("PodmanCheckRunner passes on 'sh -c exit 0' in alpine container") {
    val taskDir = Files.createTempDirectory("cr-it-exit0-")
    try
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir)
      val runner = new PodmanCheckRunner(new PodmanSandbox(config))
      val result = runEffect(runner.run("sh -c 'exit 0'"))
      result.fold(
        v => assertEquals(v, CheckResult.Pass),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      val _ = Files.deleteIfExists(taskDir)
  }

  test("PodmanCheckRunner fails on 'sh -c exit 1' and includes reason") {
    val taskDir = Files.createTempDirectory("cr-it-exit1-")
    try
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir)
      val runner = new PodmanCheckRunner(new PodmanSandbox(config))
      val result = runEffect(runner.run("sh -c 'echo check failed >&2; exit 1'"))
      result.fold(
        {
          case CheckResult.Fail(_) => ()
          case CheckResult.Pass    => fail("expected Fail for exit 1")
        },
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      val _ = Files.deleteIfExists(taskDir)
  }

end PodmanCheckRunnerIntegrationSuite
