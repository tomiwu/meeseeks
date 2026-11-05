package meeseeks.port

import kyo.*

final case class OracleInput(goal: String, output: String, rubric: Option[String] = None)

enum OracleVerdict:
  case Pass
  case Fail(reason: String)
  case Unsure(reason: String)

  /** Stop the Meeseeks. At frustration level 5 only a high-confidence stop is obeyed. */
  case Stop(reason: String, highConfidence: Boolean = false)
  case Continue

enum OracleError:
  case Unavailable(message: String)
  case InvalidInput(message: String)

/** Port: ask an oracle to judge whether a Meeseeks step result is a pass, fail, or stop. */
trait Oracle:
  def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError])
