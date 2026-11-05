package meeseeks.build

/** Result of running the build check: pass or fail with reason. */
sealed trait CheckResult
object CheckResult:
  case object Pass extends CheckResult
  final case class Fail(reason: String) extends CheckResult
