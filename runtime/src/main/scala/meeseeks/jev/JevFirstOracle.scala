package meeseeks.jev

import kyo.*
import meeseeks.port.Oracle
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict

/** Composite oracle: Jev first pass, then fallback for unsure results.
  *
  *   - When Jev is disabled (JevConfig.enabled == false): the fallback oracle decides every time.
  *   - When Jev is enabled: Jev judges first. Only an Unsure result escalates to the fallback.
  *   - Pass, Fail, Stop, and Continue verdicts from Jev are returned directly.
  */
final class JevFirstOracle(config: JevConfig, jev: Oracle, fallback: Oracle) extends Oracle:

  override def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError]) =
    if !config.enabled then fallback.judge(input)
    else
      jev.judge(input).flatMap {
        case OracleVerdict.Unsure(_) => fallback.judge(input)
        case other                   => other
      }

end JevFirstOracle

object JevFirstOracle:
  /** Build a JevFirstOracle from config. When Jev is disabled the client is never called. */
  def apply(config: JevConfig, client: JevClient, fallback: Oracle): JevFirstOracle =
    new JevFirstOracle(config, new JevOracle(client), fallback)
