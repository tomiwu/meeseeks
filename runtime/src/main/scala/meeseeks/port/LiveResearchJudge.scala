package meeseeks.port

import kyo.*
import meeseeks.research.BlindClaim
import meeseeks.research.ClaimMark
import meeseeks.research.UrlStatus

/** Live ResearchJudge: calls a Brain with a blind prompt to score claims against a rubric.
  *
  * The prompt does not include writer IDs or brain names (FR15 blind judge). Claims are presented
  * in the order passed in; shuffling is the caller's responsibility (see ResearchCoordinator).
  *
  * Expected brain response format: one line per claim — "N: MARK" where MARK is VERIFIED,
  * AGREED_ONLY, or DISPUTED. Unrecognised marks and missing lines default to DISPUTED.
  *
  * @param brain
  *   the LLM brain to call; a different model family from the research writers when possible
  */
final class LiveResearchJudge(brain: Brain) extends ResearchJudge:

  override def score(
      claims: Seq[BlindClaim],
      rubric: String
  ): Seq[ClaimMark] < (Sync & Abort[OracleError]) =
    if claims.isEmpty then Seq.empty
    else
      val prompt = buildPrompt(claims, rubric)
      Abort.run[BrainError](brain.ask(prompt)).flatMap { result =>
        result.fold(
          text => parseMarks(text, claims.length),
          e => Abort.fail(OracleError.Unavailable(s"brain error: $e")),
          t => Abort.fail(OracleError.Unavailable(t.getMessage))
        )
      }

  private def buildPrompt(claims: Seq[BlindClaim], rubric: String): String =
    val header =
      s"""You are a blind research judge. No author names or model names are given.
         |Evaluate each claim against the rubric. For each claim return one of:
         |  VERIFIED     — the claim is factually correct and the source URL supports it
         |  AGREED_ONLY  — the claim is plausible but the given URL does not confirm it
         |  DISPUTED     — the claim contradicts the source or other evidence
         |
         |Rubric: $rubric
         |
         |Claims:""".stripMargin
    val numbered = claims.zipWithIndex
      .map { (claim, i) =>
        val urlLabel = claim.urlStatus match
          case UrlStatus.Supported    => "supported"
          case UrlStatus.NotSupported => "not supported"
          case UrlStatus.Unreachable  => "unreachable"
        s"${i + 1}. \"${claim.text}\" [source URL status: $urlLabel]"
      }
      .mkString("\n")
    val footer =
      s"""
         |
         |Respond with exactly ${claims.length} lines, one per claim, in the format:
         |N: MARK
         |Example: 1: VERIFIED""".stripMargin
    s"$header\n$numbered$footer"

  private def parseMarks(text: String, count: Int): Seq[ClaimMark] =
    val linePattern = """^(\d+):\s*(.+)$""".r
    val markMap = text.linesIterator.flatMap { line =>
      linePattern.findFirstMatchIn(line.trim).flatMap { m =>
        val nOpt = m.group(1).toIntOption
        val markOpt = m.group(2).trim.toUpperCase match
          case "VERIFIED"    => Some(ClaimMark.Verified)
          case "AGREED_ONLY" => Some(ClaimMark.AgreedOnly)
          case "DISPUTED"    => Some(ClaimMark.Disputed)
          case _             => None
        nOpt.flatMap(n => markOpt.map(mark => n -> mark))
      }
    }.toMap
    (1 to count).map(i => markMap.getOrElse(i, ClaimMark.Disputed))

end LiveResearchJudge
