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
  * heartbeats so the overseer can detect stalls. At higher frustration levels splits the task into
  * children and merges their results.
  */
object Meeseeks:

  /** Creates a child Meeseeks actor. Called by the parent when it splits its task. */
  type ChildFactory = (String, Task, String) => Unit

  sealed trait Command

  private final case class PermitReply(result: Overseer.PermitResult) extends Command
  private final case class StepPassed(output: String) extends Command
  private case object StepFailed extends Command
  private final case class StepUsageLimit(reason: String) extends Command
  private final case class StepStopped(reason: String, highConfidence: Boolean) extends Command
  private case object HeartbeatTick extends Command
  private case object RetryPermit extends Command
  private final case class SolvedWorkReply(result: Overseer.SolvedWorkResult) extends Command

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
      clock: Clock = Clock.system,
      childFactory: Option[ChildFactory] = None,
      parentId: Option[String] = None,
      splitAtLevel: FrustrationLevel = FrustrationLevel.Frustrated,
      childCount: Int = 2
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        given ec: scala.concurrent.ExecutionContext = context.executionContext

        val permitAdapter = context.messageAdapter[Overseer.PermitResult](PermitReply.apply)
        val controlAdapter = context.messageAdapter[Overseer.MeeseeksControl](WrappedControl.apply)
        val solvedWorkAdapter =
          context.messageAdapter[Overseer.SolvedWorkResult](SolvedWorkReply.apply)

        val frustration = FrustrationTracker(frustrationConfig, clock)
        var stuckReportSent = false

        // Child tracking for split mode.
        var pendingChildren: Set[String] = Set.empty
        var childResults: List[String] = List.empty
        var awaitingChildren: Boolean = false

        // Register with overseer.
        parentId match
          case Some(pid) => overseer ! Overseer.RegisterChild(id, pid, controlAdapter)
          case None      => overseer ! Overseer.RegisterMeeseeks(id, controlAdapter)

        timers.startTimerAtFixedRate(HeartbeatTick, heartbeatInterval)

        // Query solved work before asking for the first permit.
        overseer ! Overseer.QuerySolvedWork(task.goal, solvedWorkAdapter)

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

        def shouldSplit(): Boolean =
          frustration.level.value >= splitAtLevel.value && childFactory.isDefined && !awaitingChildren

        def spawnChildren(): Unit =
          val ids = (1 to childCount).map(i => s"$id-child-$i").toSet
          pendingChildren = ids
          awaitingChildren = true
          childResults = List.empty
          ids.foreach { cid =>
            childFactory.foreach(_(cid, task, id))
          }

        def reportSuccess(output: String): Behavior[Command] =
          parentId match
            case Some(pid) =>
              overseer ! Overseer.ChildDone(id, pid, output)
              overseer ! Overseer.RecordSolvedWork(task.goal, output)
            case None =>
              resultSink ! s"Task complete: $output"
              overseer ! Overseer.RecordSolvedWork(task.goal, output)
          Behaviors.stopped

        Behaviors.receiveMessage {
          case SolvedWorkReply(Overseer.SolvedWork(result)) =>
            // Reuse previously solved work; skip running any steps.
            resultSink ! s"Task complete (reused): $result"
            Behaviors.stopped

          case SolvedWorkReply(Overseer.NoSolvedWork) =>
            // No prior result; begin the permit loop.
            overseer ! Overseer.AskPermit(id, permitAdapter)
            Behaviors.same

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
            reportSuccess(output)

          case StepFailed =>
            frustration.recordFailure()
            reportStuckIfNeeded()
            // Notify parent if this is a child.
            parentId.foreach { pid => overseer ! Overseer.ChildFail(id, pid) }
            if shouldSplit() then spawnChildren()
            else if !awaitingChildren then overseer ! Overseer.AskPermit(id, permitAdapter)
            Behaviors.same

          case StepStopped(reason, highConfidence) =>
            val atLevelFive = frustration.level == FrustrationLevel.Stuck
            if atLevelFive && !highConfidence then
              // Level 5: ignore low-confidence oracle stop, keep working.
              overseer ! Overseer.AskPermit(id, permitAdapter)
              Behaviors.same
            else
              parentId match
                case Some(pid) =>
                  overseer ! Overseer.ChildFail(id, pid)
                case None =>
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

          case WrappedControl(Overseer.ChildSucceeded(cid, result)) =>
            if pendingChildren.contains(cid) then
              pendingChildren = pendingChildren - cid
              childResults = result :: childResults
              if pendingChildren.isEmpty then
                val merged = childResults.reverse.mkString("\n")
                reportSuccess(merged)
              else Behaviors.same
            else Behaviors.same

          case WrappedControl(Overseer.ChildFailed(cid)) =>
            if pendingChildren.contains(cid) then
              pendingChildren = pendingChildren - cid
              frustration.recordFailure()
              reportStuckIfNeeded()
              if pendingChildren.isEmpty then
                awaitingChildren = false
                if shouldSplit() then spawnChildren()
                else overseer ! Overseer.AskPermit(id, permitAdapter)
            Behaviors.same
        }
      }
    }

end Meeseeks
