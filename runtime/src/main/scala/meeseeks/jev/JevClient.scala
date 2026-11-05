package meeseeks.jev

import kyo.*
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict

/** Port: send one judge request to the Jev API and return the verdict.
  *
  * The live implementation calls the HTTP endpoint. Tests use FakeJevClient.
  */
trait JevClient:
  def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError])
