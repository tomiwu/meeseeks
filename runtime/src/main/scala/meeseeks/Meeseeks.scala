package meeseeks

import kyo.*
import meeseeks.port.Brain
import meeseeks.port.BrainError
import meeseeks.port.Oracle
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

/** One actor per Meeseeks.
  *
  * Runs a permit loop: register with the overseer, ask for a permit, run one brain step, check the
  * oracle verdict. Stops when the oracle passes, sending one result message to resultSink. Sends
  * heartbeats so the overseer can detect stalls.
  */
object Meeseeks:

  sealed trait Command

  private final case class PermitReply(result: Overseer.PermitResult) extends Command
  private final case class StepPassed(output: String) extends Command
  private case object StepFailed extends Command
  private case object HeartbeatTick extends Command
  private case object RetryPermit extends Command

  /** Overseer control messages routed through a message adapter. */
  final case class WrappedControl(msg: Overseer.MeeseeksControl) extends Command

  def apply(
      id: String,
      task: Task,
      overseer: ActorRef[Overseer.Command],
      brain: Brain,
      oracle: Oracle,
      resultSink: ActorRef[String],
      permitRetryDelay: FiniteDuration = 500.millis,
      heartbeatInterval: FiniteDuration = 5.seconds
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        given ec: scala.concurrent.ExecutionContext = context.executionContext

        val permitAdapter = context.messageAdapter[Overseer.PermitResult](PermitReply.apply)
        val controlAdapter = context.messageAdapter[Overseer.MeeseeksControl](WrappedControl.apply)

        overseer ! Overseer.RegisterMeeseeks(id, controlAdapter)
        timers.startTimerAtFixedRate(HeartbeatTick, heartbeatInterval)
        overseer ! Overseer.AskPermit(id, permitAdapter)

        def runStep(): Unit =
          val effect: Command < (Sync & Abort[BrainError] & Abort[OracleError]) =
            brain.ask(task.goal).flatMap { output =>
              oracle.judge(OracleInput(task.goal, output)).map {
                case OracleVerdict.Pass => StepPassed(output): Command
                case _                  => StepFailed: Command
              }
            }
          val safeEffect: Command < Sync =
            Abort.run[BrainError](Abort.run[OracleError](effect)).map { outerResult =>
              outerResult.fold(
                innerResult =>
                  innerResult.fold(
                    cmd => cmd,
                    _ => StepFailed,
                    _ => StepFailed
                  ),
                _ => StepFailed,
                _ => StepFailed
              )
            }
          context.pipeToSelf(KyoStep.runToFuture(safeEffect)) {
            case Success(cmd) => cmd
            case Failure(_)   => StepFailed
          }

        Behaviors.receiveMessage {
          case PermitReply(Overseer.Granted) =>
            runStep()
            Behaviors.same

          case PermitReply(Overseer.Denied) =>
            timers.startSingleTimer(RetryPermit, permitRetryDelay)
            Behaviors.same

          case RetryPermit =>
            overseer ! Overseer.AskPermit(id, permitAdapter)
            Behaviors.same

          case StepPassed(output) =>
            resultSink ! s"Task complete: $output"
            Behaviors.stopped

          case StepFailed =>
            overseer ! Overseer.AskPermit(id, permitAdapter)
            Behaviors.same

          case HeartbeatTick =>
            overseer ! Overseer.Heartbeat(id)
            Behaviors.same

          case WrappedControl(Overseer.StopMeeseeks) =>
            Behaviors.stopped
        }
      }
    }

end Meeseeks
