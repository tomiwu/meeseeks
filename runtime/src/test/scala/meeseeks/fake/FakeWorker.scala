package meeseeks.fake

import kyo.*
import meeseeks.port.WorkItem
import meeseeks.port.WorkResult
import meeseeks.port.Worker
import meeseeks.port.WorkerError
import scala.collection.mutable

/** In-memory Worker fake that returns scripted results. Dequeues one step per call. */
final class FakeWorker(queue: mutable.Queue[Script[WorkerError, WorkResult]]) extends Worker:
  override def submit(item: WorkItem): WorkResult < (Sync & Abort[WorkerError]) =
    Sync.defer:
      if queue.isEmpty then Abort.fail(WorkerError.Unreachable("no scripted responses remain"))
      else
        val step = queue.dequeue()
        step.delay.foreach(d => Thread.sleep(d.toMillis))
        step.response match
          case Right(result) => result
          case Left(error)   => Abort.fail(error)

object FakeWorker:
  def scripted(steps: Script[WorkerError, WorkResult]*): FakeWorker =
    new FakeWorker(mutable.Queue.from(steps))
  def withResults(results: WorkResult*): FakeWorker =
    scripted(results.map(Script.ok[WorkerError, WorkResult])*)
  def withError(error: WorkerError): FakeWorker =
    scripted(Script.fail(error))
