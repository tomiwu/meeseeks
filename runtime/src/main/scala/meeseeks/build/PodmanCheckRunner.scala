package meeseeks.build

import kyo.*
import meeseeks.port.Sandbox
import meeseeks.port.SandboxError

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
      result.fold(
        r =>
          if r.exitCode == 0 then CheckResult.Pass
          else
            val raw = if r.stderr.nonEmpty then r.stderr else r.stdout
            CheckResult.Fail(raw.take(500).trim)
        ,
        {
          case SandboxError.Unavailable(msg) => Abort.fail(CheckError.SandboxUnavailable(msg))
          case SandboxError.Timeout(msg) =>
            Abort.fail(CheckError.ExecutionFailed(s"timeout: $msg"))
          case SandboxError.ContainerFailed(msg) => Abort.fail(CheckError.ExecutionFailed(msg))
        },
        t => Abort.fail(CheckError.ExecutionFailed(t.getMessage))
      )
    }

end PodmanCheckRunner
