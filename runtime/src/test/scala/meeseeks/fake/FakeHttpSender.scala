package meeseeks.fake

import kyo.*
import meeseeks.brain.HttpSender
import meeseeks.port.BrainError
import scala.collection.mutable

/** Scripted HttpSender for unit tests. No real network calls. */
final class FakeHttpSender(queue: mutable.Queue[Script[BrainError, (Int, String)]])
    extends HttpSender:

  val calls: mutable.ListBuffer[(String, Map[String, String], String)] = mutable.ListBuffer.empty

  override def post(
      url: String,
      headers: Map[String, String],
      body: String
  ): (Int, String) < (Sync & Abort[BrainError]) =
    Sync.defer:
      val _ = calls += ((url, headers, body))
      if queue.isEmpty then Abort.fail(BrainError.Unavailable("no scripted responses remain"))
      else
        val step = queue.dequeue()
        step.response match
          case Right(value) => value
          case Left(error)  => Abort.fail(error)

object FakeHttpSender:
  def withResponses(responses: (Int, String)*): FakeHttpSender =
    new FakeHttpSender(
      mutable.Queue.from(responses.map(r => Script.ok[BrainError, (Int, String)](r)))
    )
  def withError(error: BrainError): FakeHttpSender =
    new FakeHttpSender(
      mutable.Queue.from(List(Script.fail[BrainError, (Int, String)](error)))
    )
  def empty(): FakeHttpSender = new FakeHttpSender(mutable.Queue.empty)
