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
  private sealed trait PermitMsg extends Command
  private sealed trait StepMsg extends Command

  private final case class SolvedWorkReply(result: Overseer.SolvedWorkResult) extends Command
  private final case class PermitReply(result: Overseer.PermitResult) extends PermitMsg
  private case object RetryPermit extends PermitMsg
  private final case class StepPassed(output: String) extends StepMsg
  private case object StepFailed extends StepMsg
  private final case class StepStopped(reason: String, highConfidence: Boolean) extends StepMsg
  private final case class StepUsageLimit(reason: String) extends StepMsg
  private case object HeartbeatTick extends Command

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

        var pendingChildren: Set[String] = Set.empty
        var childResults: List[String] = List.empty
        var awaitingChildren: Boolean = false

        parentId match
          case Some(pid) => overseer ! Overseer.RegisterChild(id, pid, controlAdapter)
          case None      => overseer ! Overseer.RegisterMeeseeks(id, controlAdapter)

        timers.startTimerAtFixedRate(HeartbeatTick, heartbeatInterval)

        // Top-level Meeseeks only: query solved work before the first permit.
        // Children are always spawned to do new work; skip the cache check so
        // a sibling's completed result cannot short-circuit a pending child.
        if parentId.isEmpty then overseer ! Overseer.QuerySolvedWork(task.goal, solvedWorkAdapter)
        else overseer ! Overseer.AskPermit(id, permitAdapter)

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
            case None =>
              resultSink ! s"Task complete: $output"
              overseer ! Overseer.RecordSolvedWork(task.goal, output)
          Behaviors.stopped

        def handleSolvedWork(msg: SolvedWorkReply): Behavior[Command] =
          msg match
            case SolvedWorkReply(Overseer.SolvedWork(result)) =>
              resultSink ! s"Task complete (reused): $result"
              Behaviors.stopped
            case SolvedWorkReply(Overseer.NoSolvedWork) =>
              overseer ! Overseer.AskPermit(id, permitAdapter)
              Behaviors.same

        def handlePermit(msg: PermitMsg): Behavior[Command] =
          msg match
            case PermitReply(Overseer.Granted) =>
              runStep()
              Behaviors.same
            case PermitReply(Overseer.Denied) =>
              timers.startSingleTimer(RetryPermit, permitRetryDelay)
              Behaviors.same
            case RetryPermit =>
              overseer ! Overseer.AskPermit(id, permitAdapter)
              Behaviors.same

        def onUsageLimit(reason: String): Behavior[Command] =
          overseer ! Overseer.OpenBreaker(s"usage limit: $reason")
          timers.startSingleTimer(RetryPermit, permitRetryDelay)
          Behaviors.same

        def onStepFailed(): Behavior[Command] =
          frustration.recordFailure()
          reportStuckIfNeeded()
          parentId.foreach { pid => overseer ! Overseer.ChildFail(id, pid) }
          if shouldSplit() then spawnChildren()
          else if !awaitingChildren then overseer ! Overseer.AskPermit(id, permitAdapter)
          Behaviors.same

        def onStepStopped(reason: String, highConfidence: Boolean): Behavior[Command] =
          val atLevelFive = frustration.level == FrustrationLevel.Stuck
          if atLevelFive && !highConfidence then
            overseer ! Overseer.AskPermit(id, permitAdapter)
            Behaviors.same
          else
            parentId match
              case Some(pid) =>
                overseer ! Overseer.ChildFail(id, pid)
              case None =>
                resultSink ! s"Task stopped: $reason"
            Behaviors.stopped

        def handleStep(msg: StepMsg): Behavior[Command] =
          msg match
            case StepPassed(output)      => reportSuccess(output)
            case StepFailed              => onStepFailed()
            case StepStopped(reason, hc) => onStepStopped(reason, hc)
            case StepUsageLimit(reason)  => onUsageLimit(reason)

        def onChildSucceeded(cid: String, result: String): Behavior[Command] =
          if pendingChildren.contains(cid) then
            pendingChildren = pendingChildren - cid
            childResults = result :: childResults
            if pendingChildren.isEmpty then
              val merged = childResults.reverse.mkString("\n")
              reportSuccess(merged)
            else Behaviors.same
          else Behaviors.same

        def onChildFailed(cid: String): Behavior[Command] =
          if pendingChildren.contains(cid) then
            pendingChildren = pendingChildren - cid
            frustration.recordFailure()
            reportStuckIfNeeded()
            if pendingChildren.isEmpty then
              awaitingChildren = false
              if shouldSplit() then spawnChildren()
              else overseer ! Overseer.AskPermit(id, permitAdapter)
          Behaviors.same

        def handleControl(msg: Overseer.MeeseeksControl): Behavior[Command] =
          msg match
            case Overseer.StopMeeseeks           => Behaviors.stopped
            case Overseer.ChildSucceeded(cid, r) => onChildSucceeded(cid, r)
            case Overseer.ChildFailed(cid)       => onChildFailed(cid)

        Behaviors.receiveMessage {
          case msg: SolvedWorkReply => handleSolvedWork(msg)
          case msg: PermitMsg       => handlePermit(msg)
          case msg: StepMsg         => handleStep(msg)
          case HeartbeatTick        => overseer ! Overseer.Heartbeat(id); Behaviors.same
          case WrappedControl(msg)  => handleControl(msg)
        }
      }
    }

end Meeseeks
