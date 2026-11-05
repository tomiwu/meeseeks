package meeseeks.research

/** A claim presented to the judge. Contains no writer ID or brain name. */
final case class BlindClaim(text: String, sourceUrl: String, urlStatus: UrlStatus)
