package meeseeks.fake

import kyo.*
import meeseeks.port.Oracle
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict
import scala.collection.mutable

/** In-memory Oracle fake that returns scripted verdicts. Dequeues one step per call. */
final class FakeOracle(queue: mutable.Queue[Script[OracleError, OracleVerdict]]) extends Oracle:
  override def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError]) =
    Sync.defer:
      if queue.isEmpty then Abort.fail(OracleError.Unavailable("no scripted responses remain"))
      else
        val step = queue.dequeue()
        step.delay.foreach(d => Thread.sleep(d.toMillis))
        step.response match
          case Right(verdict) => verdict
          case Left(error)    => Abort.fail(error)

object FakeOracle:
  def scripted(steps: Script[OracleError, OracleVerdict]*): FakeOracle =
    new FakeOracle(mutable.Queue.from(steps))
  def withVerdicts(verdicts: OracleVerdict*): FakeOracle =
    scripted(verdicts.map(Script.ok[OracleError, OracleVerdict])*)
  def withError(error: OracleError): FakeOracle =
    scripted(Script.fail(error))
