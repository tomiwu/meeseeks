package meeseeks.build

import kyo.*
import meeseeks.port.Oracle
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict

/** Oracle for build tasks.
  *
  *   1. Rejects any change to test files by the code writer (AT5). 2. Runs the check command in an
  *      external check runner (AT4).
  *
  * Self-review is structurally impossible: judge uses only CheckRunner, never Brain.
  */
final class BuildCheckOracle(
    guard: TestFileGuard,
    runner: CheckRunner,
    checkCommand: String
) extends Oracle:

  override def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError]) =
    Sync.defer(guard.detectChange()).flatMap(onGuard)

  private def onGuard(change: Option[String]): OracleVerdict < (Sync & Abort[OracleError]) =
    change match
      case Some(reason) => OracleVerdict.Fail(s"test file changed by code writer: $reason")
      case None         => runCheck()

  private def runCheck(): OracleVerdict < (Sync & Abort[OracleError]) =
    Abort.run[CheckError](runner.run(checkCommand)).flatMap(onRun)

  private def onRun(
      r: Result[CheckError, CheckResult]
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    r.fold(onCheckResult, onCheckError, onCheckPanic)

  private def onCheckResult(result: CheckResult): OracleVerdict =
    result match
      case CheckResult.Pass    => OracleVerdict.Pass
      case CheckResult.Fail(s) => OracleVerdict.Fail(s)

  private def onCheckError(e: CheckError): OracleVerdict < (Sync & Abort[OracleError]) =
    Abort.fail(OracleError.Unavailable(e.toString))

  private def onCheckPanic(t: Throwable): OracleVerdict < (Sync & Abort[OracleError]) =
    Abort.fail(OracleError.Unavailable(t.getMessage))

end BuildCheckOracle
