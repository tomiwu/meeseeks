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
    Sync
      .defer {
        guard.detectChange()
      }
      .flatMap {
        case Some(reason) =>
          OracleVerdict.Fail(s"test file changed by code writer: $reason")
        case None =>
          Abort.run[CheckError](runner.run(checkCommand)).flatMap { r =>
            r.fold(
              {
                case CheckResult.Pass    => OracleVerdict.Pass
                case CheckResult.Fail(s) => OracleVerdict.Fail(s)
              },
              e => Abort.fail(OracleError.Unavailable(e.toString)),
              t => Abort.fail(OracleError.Unavailable(t.getMessage))
            )
          }
      }

end BuildCheckOracle
