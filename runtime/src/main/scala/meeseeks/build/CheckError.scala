package meeseeks.build

/** Error running the build check: sandbox unavailable or execution failed. */
sealed trait CheckError
object CheckError:
  final case class SandboxUnavailable(message: String) extends CheckError
  final case class ExecutionFailed(message: String) extends CheckError
