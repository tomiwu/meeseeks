package meeseeks.research

/** The final research report: rubric plus all claims with their marks. */
final case class ResearchReport(rubric: String, markedClaims: Seq[MarkedClaim])
