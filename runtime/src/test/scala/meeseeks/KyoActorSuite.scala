package meeseeks

import kyo.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** Proves: a Kyo IO effect runs inside a Pekko actor via Future + pipeToSelf. */
class KyoActorSuite extends munit.FunSuite:

  import KyoActorSuite.*

  test("Kyo IO effect delivers result to actor via pipeToSelf") {
    val kit = ActorTestKit()
    val probe = kit.createTestProbe[Int]()

    val actor = kit.spawn(WorkerActor(probe.ref), "kyo-worker")
    actor ! WorkerActor.DoWork

    assertEquals(probe.receiveMessage(5.seconds), 42)

    kit.shutdownTestKit()
  }

object KyoActorSuite:

  object WorkerActor:
    sealed trait Cmd
    case object DoWork extends Cmd
    private case class WorkDone(result: Int) extends Cmd
    private case class WorkFailed(ex: Throwable) extends Cmd

    def apply(replyTo: ActorRef[Int]): Behavior[Cmd] =
      Behaviors.setup { ctx =>
        given ec: scala.concurrent.ExecutionContext = ctx.executionContext

        Behaviors.receiveMessage:
          case DoWork =>
            // Create a Kyo IO effect and run it via KyoStep.runToFuture.
            // The actor receives the result through pipeToSelf.
            val effect: Int < IO = IO { 42 }
            val future: Future[Int] = KyoStep.runToFuture(effect)
            ctx.pipeToSelf(future):
              case Success(n) => WorkDone(n)
              case Failure(e) => WorkFailed(e)
            Behaviors.same

          case WorkDone(n) =>
            replyTo ! n
            Behaviors.stopped

          case WorkFailed(e) =>
            ctx.log.error("Kyo step failed", e)
            Behaviors.stopped
      }
