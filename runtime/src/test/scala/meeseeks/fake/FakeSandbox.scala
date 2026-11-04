package meeseeks.fake

import kyo.*
import meeseeks.port.Sandbox
import meeseeks.port.SandboxError
import meeseeks.port.SandboxResult
import scala.collection.mutable

/** In-memory Sandbox fake that returns scripted results. Dequeues one step per call. */
final class FakeSandbox(queue: mutable.Queue[Script[SandboxError, SandboxResult]]) extends Sandbox:
  override def run(command: String): SandboxResult < (Sync & Abort[SandboxError]) =
    Sync.defer:
      if queue.isEmpty then Abort.fail(SandboxError.Unavailable("no scripted responses remain"))
      else
        val step = queue.dequeue()
        step.delay.foreach(d => Thread.sleep(d.toMillis))
        step.response match
          case Right(result) => result
          case Left(error)   => Abort.fail(error)

object FakeSandbox:
  def scripted(steps: Script[SandboxError, SandboxResult]*): FakeSandbox =
    new FakeSandbox(mutable.Queue.from(steps))
  def withResults(results: SandboxResult*): FakeSandbox =
    scripted(results.map(Script.ok[SandboxError, SandboxResult])*)
  def withError(error: SandboxError): FakeSandbox =
    scripted(Script.fail(error))
