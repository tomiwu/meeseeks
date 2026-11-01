package meeseeks.port

import kyo.*

final case class OracleInput(goal: String, output: String, rubric: Option[String] = None)

enum OracleVerdict:
  case Pass
  case Fail(reason: String)
  case Unsure(reason: String)
  case Stop(reason: String)
  case Continue

enum OracleError:
  case Unavailable(message: String)
  case InvalidInput(message: String)

/** Port: ask an oracle to judge whether a Meeseeks step result is a pass, fail, or stop. */
trait Oracle:
  def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError])
