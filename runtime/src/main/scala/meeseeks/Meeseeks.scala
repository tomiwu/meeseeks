package meeseeks

import meeseeks.port.Brain
import meeseeks.port.Oracle
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
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

  private final class LoopState:
    var stuckReportSent: Boolean = false
    var pendingChildren: Set[String] = Set.empty
    var childResults: List[String] = List.empty
    var awaitingChildren: Boolean = false

  private final class LoopEnv(
      val context: ActorContext[Command],
      val timers: TimerScheduler[Command],
      val id: String,
      val task: Task,
      val overseer: ActorRef[Overseer.Command],
      val brain: Brain,
      val oracle: Oracle,
      val resultSink: ActorRef[String],
      val permitRetryDelay: FiniteDuration,
      val heartbeatInterval: FiniteDuration,
      val stepConfig: StepConfig,
      val frustrationConfig: FrustrationConfig,
      val frustration: FrustrationTracker,
      val childFactory: Option[ChildFactory],
      val parentId: Option[String],
      val splitAtLevel: FrustrationLevel,
      val childCount: Int,
      val permitAdapter: ActorRef[Overseer.PermitResult],
      val controlAdapter: ActorRef[Overseer.MeeseeksControl],
      val solvedWorkAdapter: ActorRef[Overseer.SolvedWorkResult],
      val state: LoopState
  )

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
        val permitAdapter = context.messageAdapter[Overseer.PermitResult](PermitReply.apply)
        val controlAdapter = context.messageAdapter[Overseer.MeeseeksControl](WrappedControl.apply)
        val solvedWorkAdapter =
          context.messageAdapter[Overseer.SolvedWorkResult](SolvedWorkReply.apply)
        val env = new LoopEnv(
          context,
          timers,
          id,
          task,
          overseer,
          brain,
          oracle,
          resultSink,
          permitRetryDelay,
          heartbeatInterval,
          stepConfig,
          frustrationConfig,
          FrustrationTracker(frustrationConfig, clock),
          childFactory,
          parentId,
          splitAtLevel,
          childCount,
          permitAdapter,
          controlAdapter,
          solvedWorkAdapter,
          new LoopState
        )
        start(env)
      }
    }

  private def start(env: LoopEnv): Behavior[Command] =
    register(env)
    env.timers.startTimerAtFixedRate(HeartbeatTick, env.heartbeatInterval)
    // Top-level Meeseeks only: query solved work before the first permit.
    // Children are always spawned to do new work; skip the cache check so
    // a sibling's completed result cannot short-circuit a pending child.
    if env.parentId.isEmpty then askSolvedWork(env)
    else askPermit(env)
    Behaviors.receiveMessage(onMessage(env))

  private def register(env: LoopEnv): Unit =
    env.parentId match
      case Some(pid) =>
        env.overseer ! Overseer.RegisterChild(env.id, pid, env.controlAdapter)
      case None =>
        env.overseer ! Overseer.RegisterMeeseeks(env.id, env.controlAdapter)

  private def askSolvedWork(env: LoopEnv): Unit =
    env.overseer ! Overseer.QuerySolvedWork(env.task.goal, env.solvedWorkAdapter)

  private def askPermit(env: LoopEnv): Unit =
    env.overseer ! Overseer.AskPermit(env.id, env.permitAdapter)

  private def onMessage(env: LoopEnv)(cmd: Command): Behavior[Command] =
    cmd match
      case msg: SolvedWorkReply => handleSolvedWork(env, msg)
      case other                => onAfterSolved(env, other)

  private def onAfterSolved(env: LoopEnv, cmd: Command): Behavior[Command] =
    cmd match
      case msg: PermitMsg => handlePermit(env, msg)
      case other          => onAfterPermit(env, other)

  private def onAfterPermit(env: LoopEnv, cmd: Command): Behavior[Command] =
    cmd match
      case msg: StepMsg => handleStep(env, msg)
      case other        => onAfterStep(env, other)

  private def onAfterStep(env: LoopEnv, cmd: Command): Behavior[Command] =
    cmd match
      case HeartbeatTick =>
        env.overseer ! Overseer.Heartbeat(env.id)
        Behaviors.same
      case other => onControlMessage(env, other)

  private def onControlMessage(env: LoopEnv, cmd: Command): Behavior[Command] =
    cmd match
      case WrappedControl(msg) => handleControl(env, msg)
      case _                   => Behaviors.same

  private def runStep(env: LoopEnv): Unit =
    given scala.concurrent.ExecutionContext = env.context.executionContext
    env.context.pipeToSelf(
      KyoStep.runStep(env.brain, env.oracle, env.task, env.stepConfig)
    )(stepToCommand)

  private def stepToCommand(result: scala.util.Try[StepResult]): Command =
    result match
      case Success(step) => commandForStep(step)
      case Failure(_)    => StepFailed

  private def commandForStep(step: StepResult): Command =
    step match
      case StepResult.Passed(output) => StepPassed(output)
      case other                     => commandForFailedStep(other)

  private def commandForFailedStep(step: StepResult): Command =
    step match
      case StepResult.Failed(_) => StepFailed
      case other                => commandForLimit(other)

  private def commandForLimit(step: StepResult): Command =
    step match
      case StepResult.UsageLimitError(msg) => StepUsageLimit(msg)
      case other                           => commandForStop(other)

  private def commandForStop(step: StepResult): Command =
    step match
      case StepResult.OracleStop(reason, highConfidence) =>
        StepStopped(reason, highConfidence)
      case _ => StepFailed

  private def reportStuckIfNeeded(env: LoopEnv): Unit =
    if isUnreportedStuck(env) then sendStuck(env)

  private def isUnreportedStuck(env: LoopEnv): Boolean =
    env.frustration.level == FrustrationLevel.Stuck && !env.state.stuckReportSent

  private def sendStuck(env: LoopEnv): Unit =
    env.overseer ! Overseer.StuckReport(
      env.id,
      env.frustrationConfig.levelNames.getOrElse(FrustrationLevel.Stuck, "stuck")
    )
    env.state.stuckReportSent = true

  private def shouldSplit(env: LoopEnv): Boolean =
    levelHighEnough(env) && canSpawn(env)

  private def levelHighEnough(env: LoopEnv): Boolean =
    env.frustration.level.value >= env.splitAtLevel.value

  private def canSpawn(env: LoopEnv): Boolean =
    env.childFactory.isDefined && !env.state.awaitingChildren

  private def spawnChildren(env: LoopEnv): Unit =
    val ids = (1 to env.childCount).map(i => s"${env.id}-child-$i").toSet
    env.state.pendingChildren = ids
    env.state.awaitingChildren = true
    env.state.childResults = List.empty
    ids.foreach { cid =>
      env.childFactory.foreach(_(cid, env.task, env.id))
    }

  private def reportSuccess(env: LoopEnv, output: String): Behavior[Command] =
    env.parentId match
      case Some(pid) =>
        env.overseer ! Overseer.ChildDone(env.id, pid, output)
      case None =>
        env.resultSink ! s"Task complete: $output"
        env.overseer ! Overseer.RecordSolvedWork(env.task.goal, output)
    Behaviors.stopped

  private def handleSolvedWork(env: LoopEnv, msg: SolvedWorkReply): Behavior[Command] =
    msg match
      case SolvedWorkReply(Overseer.SolvedWork(result)) =>
        env.resultSink ! s"Task complete (reused): $result"
        Behaviors.stopped
      case SolvedWorkReply(Overseer.NoSolvedWork) =>
        askPermit(env)
        Behaviors.same

  private def handlePermit(env: LoopEnv, msg: PermitMsg): Behavior[Command] =
    msg match
      case PermitReply(result) => onPermitResult(env, result)
      case RetryPermit =>
        askPermit(env)
        Behaviors.same

  private def onPermitResult(
      env: LoopEnv,
      result: Overseer.PermitResult
  ): Behavior[Command] =
    result match
      case Overseer.Granted =>
        runStep(env)
        Behaviors.same
      case Overseer.Denied =>
        env.timers.startSingleTimer(RetryPermit, env.permitRetryDelay)
        Behaviors.same

  private def onUsageLimit(env: LoopEnv, reason: String): Behavior[Command] =
    env.overseer ! Overseer.OpenBreaker(s"usage limit: $reason")
    env.timers.startSingleTimer(RetryPermit, env.permitRetryDelay)
    Behaviors.same

  private def onStepFailed(env: LoopEnv): Behavior[Command] =
    env.frustration.recordFailure()
    reportStuckIfNeeded(env)
    env.parentId.foreach { pid =>
      env.overseer ! Overseer.ChildFail(env.id, pid)
    }
    afterFailure(env)
    Behaviors.same

  private def afterFailure(env: LoopEnv): Unit =
    if shouldSplit(env) then spawnChildren(env)
    else askPermitUnlessWaiting(env)

  private def askPermitUnlessWaiting(env: LoopEnv): Unit =
    if !env.state.awaitingChildren then askPermit(env)

  private def onStepStopped(
      env: LoopEnv,
      reason: String,
      highConfidence: Boolean
  ): Behavior[Command] =
    if keepWorking(env, highConfidence) then
      askPermit(env)
      Behaviors.same
    else stopForOracle(env, reason)

  private def keepWorking(env: LoopEnv, highConfidence: Boolean): Boolean =
    env.frustration.level == FrustrationLevel.Stuck && !highConfidence

  private def stopForOracle(env: LoopEnv, reason: String): Behavior[Command] =
    env.parentId match
      case Some(pid) => env.overseer ! Overseer.ChildFail(env.id, pid)
      case None      => env.resultSink ! s"Task stopped: $reason"
    Behaviors.stopped

  private def handleStep(env: LoopEnv, msg: StepMsg): Behavior[Command] =
    msg match
      case StepPassed(output) => reportSuccess(env, output)
      case other              => handleStepRest(env, other)

  private def handleStepRest(env: LoopEnv, msg: StepMsg): Behavior[Command] =
    msg match
      case StepFailed => onStepFailed(env)
      case other      => handleStepStop(env, other)

  private def handleStepStop(env: LoopEnv, msg: StepMsg): Behavior[Command] =
    msg match
      case StepStopped(reason, highConfidence) =>
        onStepStopped(env, reason, highConfidence)
      case other => handleStepLimit(env, other)

  private def handleStepLimit(env: LoopEnv, msg: StepMsg): Behavior[Command] =
    msg match
      case StepUsageLimit(reason) => onUsageLimit(env, reason)
      case _                      => onStepFailed(env)

  private def onChildSucceeded(env: LoopEnv, cid: String, result: String): Behavior[Command] =
    if env.state.pendingChildren.contains(cid) then recordChildSuccess(env, cid, result)
    else Behaviors.same

  private def recordChildSuccess(env: LoopEnv, cid: String, result: String): Behavior[Command] =
    env.state.pendingChildren = env.state.pendingChildren - cid
    env.state.childResults = result :: env.state.childResults
    if env.state.pendingChildren.isEmpty then
      reportSuccess(env, env.state.childResults.reverse.mkString("\n"))
    else Behaviors.same

  private def onChildFailed(env: LoopEnv, cid: String): Behavior[Command] =
    if env.state.pendingChildren.contains(cid) then recordChildFailure(env, cid)
    Behaviors.same

  private def recordChildFailure(env: LoopEnv, cid: String): Unit =
    env.state.pendingChildren = env.state.pendingChildren - cid
    env.frustration.recordFailure()
    reportStuckIfNeeded(env)
    if env.state.pendingChildren.isEmpty then resumeAfterChildren(env)

  private def resumeAfterChildren(env: LoopEnv): Unit =
    env.state.awaitingChildren = false
    if shouldSplit(env) then spawnChildren(env)
    else askPermit(env)

  private def handleControl(env: LoopEnv, msg: Overseer.MeeseeksControl): Behavior[Command] =
    msg match
      case Overseer.StopMeeseeks => Behaviors.stopped
      case other                 => handleChildControl(env, other)

  private def handleChildControl(
      env: LoopEnv,
      msg: Overseer.MeeseeksControl
  ): Behavior[Command] =
    msg match
      case Overseer.ChildSucceeded(cid, result) => onChildSucceeded(env, cid, result)
      case other                                => onChildFailedMsg(env, other)

  private def onChildFailedMsg(env: LoopEnv, msg: Overseer.MeeseeksControl): Behavior[Command] =
    msg match
      case Overseer.ChildFailed(cid) => onChildFailed(env, cid)
      case _                         => Behaviors.same

end Meeseeks
