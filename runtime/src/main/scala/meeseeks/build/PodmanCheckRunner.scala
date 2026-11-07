package meeseeks.build

import kyo.*
import meeseeks.port.Sandbox
import meeseeks.port.SandboxError
import meeseeks.port.SandboxResult

/** CheckRunner backed by a Sandbox.
  *
  * Maps exit code 0 to CheckResult.Pass. Maps non-zero to CheckResult.Fail with the stderr (or
  * stdout if stderr is empty), truncated to 500 characters. Maps SandboxError to CheckError.
  *
  * Inject a PodmanSandbox for production use. Inject a FakeSandbox in tests.
  */
final class PodmanCheckRunner(sandbox: Sandbox) extends CheckRunner:

  override def run(command: String): CheckResult < (Sync & Abort[CheckError]) =
    Abort.run[SandboxError](sandbox.run(command)).flatMap { result =>
      result.fold(fromRun, fromSandboxError, fromPanic)
    }

  private def fromRun(r: SandboxResult): CheckResult =
    if r.exitCode == 0 then CheckResult.Pass
    else failText(r)

  private def failText(r: SandboxResult): CheckResult =
    CheckResult.Fail(outputText(r).take(500).trim)

  private def outputText(r: SandboxResult): String =
    if r.stderr.nonEmpty then r.stderr else r.stdout

  private def fromSandboxError(
      err: SandboxError
  ): CheckResult < (Sync & Abort[CheckError]) =
    err match
      case SandboxError.Unavailable(msg) => Abort.fail(CheckError.SandboxUnavailable(msg))
      case other                         => fromTimeout(other)

  private def fromTimeout(err: SandboxError): CheckResult < (Sync & Abort[CheckError]) =
    err match
      case SandboxError.Timeout(msg) => Abort.fail(CheckError.ExecutionFailed(s"timeout: $msg"))
      case other                     => fromContainer(other)

  private def fromContainer(err: SandboxError): CheckResult < (Sync & Abort[CheckError]) =
    err match
      case SandboxError.ContainerFailed(msg) =>
        Abort.fail(CheckError.ExecutionFailed(msg))
      case _ => Abort.fail(CheckError.ExecutionFailed(err.toString))

  private def fromPanic(t: Throwable): CheckResult < (Sync & Abort[CheckError]) =
    Abort.fail(CheckError.ExecutionFailed(t.getMessage))

end PodmanCheckRunner
