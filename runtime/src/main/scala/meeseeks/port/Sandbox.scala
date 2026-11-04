package meeseeks.port

import kyo.*

final case class SandboxResult(exitCode: Int, stdout: String, stderr: String)

enum SandboxError:
  case ContainerFailed(message: String)
  case Timeout(message: String)
  case Unavailable(message: String)

/** Port: run a command in an isolated container sandbox. */
trait Sandbox:
  def run(command: String): SandboxResult < (Sync & Abort[SandboxError])
