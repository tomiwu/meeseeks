package meeseeks.fake

import kyo.*
import meeseeks.port.OracleError
import meeseeks.port.ResearchJudge
import meeseeks.research.BlindClaim
import meeseeks.research.ClaimMark
import scala.collection.mutable

/** In-memory ResearchJudge fake that returns scripted marks and records received claims. */
final class FakeResearchJudge(marks: Seq[ClaimMark]) extends ResearchJudge:

  val receivedClaims: mutable.ListBuffer[BlindClaim] = mutable.ListBuffer.empty

  override def score(
      claims: Seq[BlindClaim],
      rubric: String
  ): Seq[ClaimMark] < (Sync & Abort[OracleError]) =
    Sync.defer:
      val _ = receivedClaims ++= claims
      if marks.length < claims.length then
        Abort.fail(
          OracleError.InvalidInput(
            s"judge has ${marks.length} scripted marks but got ${claims.length} claims"
          )
        )
      else marks.take(claims.length)

object FakeResearchJudge:
  def withMarks(marks: ClaimMark*): FakeResearchJudge = new FakeResearchJudge(marks.toSeq)
