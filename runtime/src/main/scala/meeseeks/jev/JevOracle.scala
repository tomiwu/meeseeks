package meeseeks.jev

import kyo.*
import meeseeks.port.Oracle
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict

/** Oracle that delegates each judge call to the Jev API client. */
final class JevOracle(client: JevClient) extends Oracle:
  override def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError]) =
    client.judge(input)
