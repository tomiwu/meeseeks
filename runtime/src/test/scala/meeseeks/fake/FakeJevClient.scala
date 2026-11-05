package meeseeks.fake

import kyo.*
import meeseeks.jev.JevClient
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict
import scala.collection.mutable

/** In-memory JevClient fake that returns scripted verdicts. Dequeues one step per call.
  *
  * Records every input received so tests can assert on the calls made.
  */
final class FakeJevClient(queue: mutable.Queue[Script[OracleError, OracleVerdict]])
    extends JevClient:

  private val _calls: mutable.ListBuffer[OracleInput] = mutable.ListBuffer.empty

  def calls: List[OracleInput] = _calls.toList

  override def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError]) =
    Sync.defer:
      _calls += input
      if queue.isEmpty then
        Abort.fail(OracleError.Unavailable("FakeJevClient: no scripted responses remain"))
      else
        val step = queue.dequeue()
        step.delay.foreach(d => Thread.sleep(d.toMillis))
        step.response match
          case Right(verdict) => verdict
          case Left(error)    => Abort.fail(error)

object FakeJevClient:
  def scripted(steps: Script[OracleError, OracleVerdict]*): FakeJevClient =
    new FakeJevClient(mutable.Queue.from(steps))
  def withVerdicts(verdicts: OracleVerdict*): FakeJevClient =
    scripted(verdicts.map(Script.ok[OracleError, OracleVerdict])*)
  def withError(error: OracleError): FakeJevClient =
    scripted(Script.fail(error))
  def empty(): FakeJevClient =
    new FakeJevClient(mutable.Queue.empty)
