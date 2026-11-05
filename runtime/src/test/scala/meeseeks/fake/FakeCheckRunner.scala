package meeseeks.fake

import kyo.*
import meeseeks.build.CheckError
import meeseeks.build.CheckResult
import meeseeks.build.CheckRunner
import scala.collection.mutable

/** In-memory CheckRunner fake that returns scripted results. Dequeues one step per call. */
final class FakeCheckRunner(queue: mutable.Queue[Script[CheckError, CheckResult]]) extends CheckRunner:
  override def run(command: String): CheckResult < (Sync & Abort[CheckError]) =
    Sync.defer:
      if queue.isEmpty then Abort.fail(CheckError.SandboxUnavailable("no scripted responses remain"))
      else
        val step = queue.dequeue()
        step.delay.foreach(d => Thread.sleep(d.toMillis))
        step.response match
          case Right(result) => result
          case Left(error)   => Abort.fail(error)

object FakeCheckRunner:
  def scripted(steps: Script[CheckError, CheckResult]*): FakeCheckRunner =
    new FakeCheckRunner(mutable.Queue.from(steps))
  def withResults(results: CheckResult*): FakeCheckRunner =
    scripted(results.map(Script.ok[CheckError, CheckResult])*)
  def withError(error: CheckError): FakeCheckRunner =
    scripted(Script.fail(error))
