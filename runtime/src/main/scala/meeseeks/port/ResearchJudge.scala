package meeseeks.port

import kyo.*
import meeseeks.research.BlindClaim
import meeseeks.research.ClaimMark

/** Port: score a list of blind claims against a rubric. Returns one mark per claim. */
trait ResearchJudge:
  def score(claims: Seq[BlindClaim], rubric: String): Seq[ClaimMark] < (Sync & Abort[OracleError])
