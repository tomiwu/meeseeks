package meeseeks.research

/** Parses claims from a brain response string. */
object ClaimParser:
  private val Pattern = raw"""CLAIM:\s*(.+?)\s*\|\s*SOURCE:\s*(\S+)""".r

  def parse(response: String): Seq[Claim] =
    Pattern.findAllMatchIn(response).map(m => Claim(m.group(1).trim, m.group(2).trim)).toSeq
