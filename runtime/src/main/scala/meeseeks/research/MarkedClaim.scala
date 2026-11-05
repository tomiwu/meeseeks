package meeseeks.research

/** A claim with its URL verification status and judge mark. */
final case class MarkedClaim(claim: Claim, urlStatus: UrlStatus, mark: ClaimMark)
