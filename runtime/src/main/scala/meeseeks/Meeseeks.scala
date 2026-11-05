package meeseeks

import meeseeks.port.Brain
import meeseeks.port.Oracle
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
  private final case class StepUsageLimit(reason: String) extends Command
  private final case class StepStopped(reason: String, highConfidence: Boolean) extends Command
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
      heartbeatInterval: FiniteDuration = 5.seconds,
      stepConfig: StepConfig = StepConfig(),
      frustrationConfig: FrustrationConfig = FrustrationConfig(),
      clock: Clock = Clock.system
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        given ec: scala.concurrent.ExecutionContext = context.executionContext

        val permitAdapter = context.messageAdapter[Overseer.PermitResult](PermitReply.apply)
        val controlAdapter = context.messageAdapter[Overseer.MeeseeksControl](WrappedControl.apply)

        val frustration = FrustrationTracker(frustrationConfig, clock)
        var stuckReportSent = false

        overseer ! Overseer.RegisterMeeseeks(id, controlAdapter)
        timers.startTimerAtFixedRate(HeartbeatTick, heartbeatInterval)
        overseer ! Overseer.AskPermit(id, permitAdapter)

        def runStep(): Unit =
          context.pipeToSelf(KyoStep.runStep(brain, oracle, task, stepConfig)) {
            case Success(StepResult.Passed(output))       => StepPassed(output)
            case Success(StepResult.Failed(_))            => StepFailed
            case Success(StepResult.UsageLimitError(msg)) => StepUsageLimit(msg)
            case Success(StepResult.OracleStop(r, hc))    => StepStopped(r, hc)
            case Failure(_)                               => StepFailed
          }

        def reportStuckIfNeeded(): Unit =
          if frustration.level == FrustrationLevel.Stuck && !stuckReportSent then
            overseer ! Overseer.StuckReport(
              id,
              frustrationConfig.levelNames.getOrElse(FrustrationLevel.Stuck, "stuck")
            )
            stuckReportSent = true

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
            frustration.recordFailure()
            reportStuckIfNeeded()
            overseer ! Overseer.AskPermit(id, permitAdapter)
            Behaviors.same

          case StepStopped(reason, highConfidence) =>
            val atLevelFive = frustration.level == FrustrationLevel.Stuck
            if atLevelFive && !highConfidence then
              // Level 5: ignore low-confidence oracle stop, keep working.
              overseer ! Overseer.AskPermit(id, permitAdapter)
              Behaviors.same
            else
              resultSink ! s"Task stopped: $reason"
              Behaviors.stopped

          case StepUsageLimit(reason) =>
            overseer ! Overseer.OpenBreaker(s"usage limit: $reason")
            timers.startSingleTimer(RetryPermit, permitRetryDelay)
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
