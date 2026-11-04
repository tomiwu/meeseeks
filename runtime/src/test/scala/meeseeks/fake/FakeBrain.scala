package meeseeks.fake

import kyo.*
import meeseeks.port.Brain
import meeseeks.port.BrainError
import scala.collection.mutable

/** In-memory Brain fake that returns scripted responses. Dequeues one step per call. */
final class FakeBrain(queue: mutable.Queue[Script[BrainError, String]]) extends Brain:
  override def ask(prompt: String): String < (Sync & Abort[BrainError]) =
    Sync.defer:
      if queue.isEmpty then Abort.fail(BrainError.Unavailable("no scripted responses remain"))
      else
        val step = queue.dequeue()
        step.delay.foreach(d => Thread.sleep(d.toMillis))
        step.response match
          case Right(value) => value
          case Left(error)  => Abort.fail(error)

object FakeBrain:
  def scripted(steps: Script[BrainError, String]*): FakeBrain =
    new FakeBrain(mutable.Queue.from(steps))
  def withValues(values: String*): FakeBrain =
    scripted(values.map(Script.ok[BrainError, String])*)
  def withError(error: BrainError): FakeBrain =
    scripted(Script.fail(error))
