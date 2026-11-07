package meeseeks

import java.time.Instant
import meeseeks.port.DiskMonitor
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.Effect
import org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior
import scala.concurrent.duration.*

/** One persistent overseer actor.
  *
  * Holds the permit gate, kill state, circuit breaker, Meeseeks tree, and solved-work cache as
  * journal state. Detects stalls with heartbeats. Kills a stalled Meeseeks and restarts it. All
  * gate state survives a restart because it lives in the journal.
  */
object Overseer:

  // ---- Commands ----

  sealed trait Command

  sealed trait RegistrationCmd extends Command
  sealed trait ActivityCmd extends Command
  sealed trait BreakerCmd extends Command
  sealed trait ChildWorkCmd extends Command
  sealed trait AdminCmd extends Command

  /** A Meeseeks registers itself with its control ref. The overseer records the initial heartbeat.
    */
  final case class RegisterMeeseeks(id: String, ref: ActorRef[MeeseeksControl])
      extends RegistrationCmd

  /** A child Meeseeks registers itself with its parent ID. */
  final case class RegisterChild(childId: String, parentId: String, ref: ActorRef[MeeseeksControl])
      extends RegistrationCmd

  /** A Meeseeks sends a periodic heartbeat to prove it is still running. */
  final case class Heartbeat(meeseeksId: String) extends ActivityCmd

  /** A Meeseeks asks for a permit before its next loop iteration. */
  final case class AskPermit(meeseeksId: String, replyTo: ActorRef[PermitResult])
      extends ActivityCmd

  /** A Meeseeks at level 5 reports that it is stuck and cannot make progress. */
  final case class StuckReport(meeseeksId: String, reason: String) extends ActivityCmd

  /** Stop all work. After this no permits are given. Workers receive a stop order. */
  case object Kill extends BreakerCmd

  /** Open the circuit breaker with a reason. No permits until ResumeBreaker. */
  final case class OpenBreaker(reason: String) extends BreakerCmd

  /** Close the circuit breaker. Permits resume. Only Tomasz sends this. */
  case object ResumeBreaker extends BreakerCmd

  /** A child reports that one of its steps succeeded. Routes ChildSucceeded to the parent. */
  final case class ChildDone(childId: String, parentId: String, result: String) extends ChildWorkCmd

  /** A child reports that one of its steps failed. Routes ChildFailed to the parent. */
  final case class ChildFail(childId: String, parentId: String) extends ChildWorkCmd

  /** Query whether this goal has already been solved. */
  final case class QuerySolvedWork(goal: String, replyTo: ActorRef[SolvedWorkResult])
      extends ChildWorkCmd

  /** Record a solved result so future Meeseeks can reuse it. */
  final case class RecordSolvedWork(goal: String, result: String) extends ChildWorkCmd

  /** Return a snapshot of the Meeseeks tree for display. */
  final case class GetTreeView(replyTo: ActorRef[TreeView]) extends AdminCmd

  /** Internal timer message: check for Meeseeks actors that have missed their heartbeat. */
  private case object CheckStalls extends AdminCmd

  /** Internal timer message: check free disk space and open the breaker if below the threshold. */
  private case object CheckDisk extends AdminCmd

  // ---- Permit result ----

  sealed trait PermitResult extends CborSerializable
  case object Granted extends PermitResult
  case object Denied extends PermitResult

  // ---- Solved-work query result ----

  sealed trait SolvedWorkResult extends CborSerializable
  final case class SolvedWork(result: String) extends SolvedWorkResult
  case object NoSolvedWork extends SolvedWorkResult

  // ---- Tree view ----

  final case class TreeView(meeseeksIds: Set[String], childParent: Map[String, String])
      extends CborSerializable

  // ---- Control messages sent to a Meeseeks by the overseer ----

  sealed trait MeeseeksControl extends CborSerializable
  case object StopMeeseeks extends MeeseeksControl

  /** Sent to a parent when one of its children finishes successfully. */
  final case class ChildSucceeded(childId: String, result: String) extends MeeseeksControl

  /** Sent to a parent when one of its children fails a step. */
  final case class ChildFailed(childId: String) extends MeeseeksControl

  // ---- Journal events ----

  sealed trait Event extends CborSerializable

  sealed trait BreakerEvt extends Event
  sealed trait TreeEvt extends Event

  /** All work was killed. No more permits. */
  case object AllKilled extends BreakerEvt

  /** Circuit breaker opened due to a resource limit or error. */
  final case class BreakerOpened(reason: String) extends BreakerEvt

  /** Circuit breaker closed. Permits resume. */
  case object BreakerClosed extends BreakerEvt

  /** A Meeseeks was added to the tree for the first time. */
  final case class MeeseeksAdded(id: String) extends TreeEvt

  /** A child Meeseeks was added to the tree with its parent reference. */
  final case class ChildAdded(childId: String, parentId: String) extends TreeEvt

  /** A solved result was recorded for future reuse. */
  final case class WorkSolved(goal: String, result: String) extends TreeEvt

  // ---- Journal state ----

  final case class State(
      killed: Boolean = false,
      breakerOpen: Boolean = false,
      meeseeksIds: Set[String] = Set.empty,
      childParent: Map[String, String] = Map.empty,
      solvedWork: Map[String, String] = Map.empty
  )

  // ---- Factory type ----

  /** Called when the overseer must restart a stalled Meeseeks. */
  type MeeseeksFactory = String => Unit

  /** Free-disk threshold: open the breaker when usable bytes fall below this value. */
  val DiskThresholdBytes: Long = 10L * 1024L * 1024L * 1024L

  // ---- Behavior ----

  private final class Runtime(
      val report: String => Unit,
      val factory: MeeseeksFactory,
      val heartbeatTimeout: FiniteDuration,
      val diskMonitor: Option[DiskMonitor]
  ):
    var refs: Map[String, ActorRef[MeeseeksControl]] = Map.empty
    var heartbeats: Map[String, Instant] = Map.empty

  def apply(
      id: String,
      factory: MeeseeksFactory = _ => (),
      notify: String => Unit = _ => (),
      diskMonitor: Option[DiskMonitor] = None,
      heartbeatTimeout: FiniteDuration = 30.seconds,
      checkInterval: FiniteDuration = 5.seconds,
      diskCheckInterval: FiniteDuration = 30.seconds
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      val runtime = new Runtime(notify, factory, heartbeatTimeout, diskMonitor)
      timers.startTimerAtFixedRate(CheckStalls, checkInterval)
      diskMonitor.foreach { _ =>
        timers.startTimerAtFixedRate(CheckDisk, diskCheckInterval)
      }
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(id),
        emptyState = State(),
        commandHandler = (state, cmd) => onCommand(runtime, state, cmd),
        eventHandler = (state, event) => onEvent(state, event)
      )
    }

  private def onCommand(rt: Runtime, state: State, cmd: Command): Effect[Event, State] =
    cmd match
      case c: RegistrationCmd => onRegistration(rt, state, c)
      case other              => onAfterRegistration(rt, state, other)

  private def onAfterRegistration(rt: Runtime, state: State, cmd: Command): Effect[Event, State] =
    cmd match
      case c: ActivityCmd => onActivity(rt, state, c)
      case other          => onAfterActivity(rt, state, other)

  private def onAfterActivity(rt: Runtime, state: State, cmd: Command): Effect[Event, State] =
    cmd match
      case c: BreakerCmd => onBreaker(rt, state, c)
      case other         => onAfterBreaker(rt, state, other)

  private def onAfterBreaker(rt: Runtime, state: State, cmd: Command): Effect[Event, State] =
    cmd match
      case c: ChildWorkCmd => onChildWork(rt, state, c)
      case other           => onAfterChild(rt, state, other)

  private def onAfterChild(rt: Runtime, state: State, cmd: Command): Effect[Event, State] =
    cmd match
      case c: AdminCmd => onAdmin(rt, state, c)
      case _           => Effect.none

  private def onEvent(state: State, event: Event): State =
    event match
      case evt: BreakerEvt => onBreakerEvent(state, evt)
      case evt: TreeEvt    => onTreeEvent(state, evt)

  private def onRegistration(
      rt: Runtime,
      state: State,
      cmd: RegistrationCmd
  ): Effect[Event, State] =
    cmd match
      case RegisterMeeseeks(mid, ref)   => addMeeseeks(rt, state, mid, ref)
      case RegisterChild(cid, pid, ref) => addChild(rt, state, cid, pid, ref)

  private def addMeeseeks(
      rt: Runtime,
      state: State,
      mid: String,
      ref: ActorRef[MeeseeksControl]
  ): Effect[Event, State] =
    rt.refs = rt.refs.updated(mid, ref)
    rt.heartbeats = rt.heartbeats.updated(mid, Instant.now())
    if state.meeseeksIds.contains(mid) then Effect.none
    else Effect.persist(MeeseeksAdded(mid))

  private def addChild(
      rt: Runtime,
      state: State,
      cid: String,
      pid: String,
      ref: ActorRef[MeeseeksControl]
  ): Effect[Event, State] =
    rt.refs = rt.refs.updated(cid, ref)
    rt.heartbeats = rt.heartbeats.updated(cid, Instant.now())
    if state.childParent.contains(cid) then Effect.none
    else Effect.persist(ChildAdded(cid, pid))

  private def onActivity(rt: Runtime, state: State, cmd: ActivityCmd): Effect[Event, State] =
    cmd match
      case Heartbeat(mid) => noteHeartbeat(rt, mid)
      case other          => onNotHeartbeat(rt, state, other)

  private def noteHeartbeat(rt: Runtime, mid: String): Effect[Event, State] =
    rt.heartbeats = rt.heartbeats.updated(mid, Instant.now())
    Effect.none

  private def onNotHeartbeat(rt: Runtime, state: State, cmd: ActivityCmd): Effect[Event, State] =
    cmd match
      case AskPermit(_, replyTo) => replyPermit(state, replyTo)
      case other                 => onStuck(rt, other)

  private def replyPermit(state: State, replyTo: ActorRef[PermitResult]): Effect[Event, State] =
    replyTo ! (if permitOpen(state) then Granted else Denied)
    Effect.none

  private def permitOpen(state: State): Boolean =
    !state.killed && !state.breakerOpen

  private def onStuck(rt: Runtime, cmd: ActivityCmd): Effect[Event, State] =
    cmd match
      case StuckReport(mid, reason) =>
        rt.report(s"Meeseeks $mid is stuck: $reason. Waiting for upstream stop.")
        Effect.none
      case _ => Effect.none

  private def onBreaker(rt: Runtime, state: State, cmd: BreakerCmd): Effect[Event, State] =
    cmd match
      case Kill  => onKill(rt, state)
      case other => onBreakerRest(rt, state, other)

  private def onBreakerRest(rt: Runtime, state: State, cmd: BreakerCmd): Effect[Event, State] =
    cmd match
      case OpenBreaker(reason) => onOpenBreaker(rt, state, reason)
      case other               => onResumeOrKill(rt, state, other)

  private def onResumeOrKill(rt: Runtime, state: State, cmd: BreakerCmd): Effect[Event, State] =
    cmd match
      case ResumeBreaker => onResumeBreaker(state)
      case _             => onKill(rt, state)

  private def onKill(rt: Runtime, state: State): Effect[Event, State] =
    if state.killed then Effect.none
    else
      Effect
        .persist[Event, State](AllKilled)
        .thenRun { _ =>
          rt.refs.values.foreach(_ ! StopMeeseeks)
        }

  private def onOpenBreaker(rt: Runtime, state: State, reason: String): Effect[Event, State] =
    if state.breakerOpen then Effect.none
    else
      Effect
        .persist[Event, State](BreakerOpened(reason))
        .thenRun { _ =>
          rt.report(s"Circuit breaker opened: $reason. Send resume to restart.")
        }

  private def onResumeBreaker(state: State): Effect[Event, State] =
    if !state.breakerOpen then Effect.none
    else Effect.persist(BreakerClosed)

  private def onChildWork(rt: Runtime, state: State, cmd: ChildWorkCmd): Effect[Event, State] =
    cmd match
      case ChildDone(cid, pid, result) => forwardChildDone(rt, cid, pid, result)
      case other                       => onChildRest(rt, state, other)

  private def forwardChildDone(
      rt: Runtime,
      cid: String,
      pid: String,
      result: String
  ): Effect[Event, State] =
    rt.refs.get(pid).foreach(_ ! ChildSucceeded(cid, result))
    Effect.none

  private def onChildRest(rt: Runtime, state: State, cmd: ChildWorkCmd): Effect[Event, State] =
    cmd match
      case ChildFail(cid, pid) => forwardChildFail(rt, cid, pid)
      case other               => onChildQuery(rt, state, other)

  private def forwardChildFail(rt: Runtime, cid: String, pid: String): Effect[Event, State] =
    rt.refs.get(pid).foreach(_ ! ChildFailed(cid))
    Effect.none

  private def onChildQuery(rt: Runtime, state: State, cmd: ChildWorkCmd): Effect[Event, State] =
    cmd match
      case QuerySolvedWork(goal, replyTo) => answerSolvedWork(state, goal, replyTo)
      case other                          => onRecordWork(state, other)

  private def answerSolvedWork(
      state: State,
      goal: String,
      replyTo: ActorRef[SolvedWorkResult]
  ): Effect[Event, State] =
    replyTo ! state.solvedWork.get(goal).fold[SolvedWorkResult](NoSolvedWork)(SolvedWork.apply)
    Effect.none

  private def onRecordWork(state: State, cmd: ChildWorkCmd): Effect[Event, State] =
    cmd match
      case RecordSolvedWork(goal, result) => recordSolvedWork(state, goal, result)
      case _                              => Effect.none

  private def recordSolvedWork(state: State, goal: String, result: String): Effect[Event, State] =
    if state.solvedWork.contains(goal) then Effect.none
    else Effect.persist(WorkSolved(goal, result))

  private def onCheckStalls(rt: Runtime): Effect[Event, State] =
    val now = Instant.now()
    val stalled = rt.heartbeats.filter { case (_, lastHb) =>
      java.time.Duration.between(lastHb, now).toMillis > rt.heartbeatTimeout.toMillis
    }.keySet
    stalled.foreach { mid =>
      rt.refs.get(mid).foreach(_ ! StopMeeseeks)
      rt.refs = rt.refs.removed(mid)
      rt.heartbeats = rt.heartbeats.removed(mid)
      rt.factory(mid)
    }
    Effect.none

  private def onCheckDisk(rt: Runtime, state: State): Effect[Event, State] =
    rt.diskMonitor match
      case Some(dm) => onDiskSample(rt, state, dm)
      case None     => Effect.none

  private def onDiskSample(rt: Runtime, state: State, dm: DiskMonitor): Effect[Event, State] =
    if diskLow(state, dm) then openForDisk(rt)
    else Effect.none

  private def diskLow(state: State, dm: DiskMonitor): Boolean =
    !state.breakerOpen && dm.freeBytes() < DiskThresholdBytes

  private def openForDisk(rt: Runtime): Effect[Event, State] =
    Effect
      .persist[Event, State](BreakerOpened("free disk below 10 GB"))
      .thenRun { _ =>
        rt.report("Circuit breaker opened: free disk below 10 GB. Send resume to restart.")
      }

  private def onAdmin(rt: Runtime, state: State, cmd: AdminCmd): Effect[Event, State] =
    cmd match
      case GetTreeView(replyTo) =>
        replyTo ! TreeView(state.meeseeksIds, state.childParent)
        Effect.none
      case other => onAdminRest(rt, state, other)

  private def onAdminRest(rt: Runtime, state: State, cmd: AdminCmd): Effect[Event, State] =
    cmd match
      case CheckStalls => onCheckStalls(rt)
      case other       => onAdminDisk(rt, state, other)

  private def onAdminDisk(rt: Runtime, state: State, cmd: AdminCmd): Effect[Event, State] =
    cmd match
      case CheckDisk => onCheckDisk(rt, state)
      case _         => Effect.none

  private def onBreakerEvent(state: State, evt: BreakerEvt): State =
    evt match
      case AllKilled => state.copy(killed = true)
      case other     => onBreakerOpened(state, other)

  private def onBreakerOpened(state: State, evt: BreakerEvt): State =
    evt match
      case BreakerOpened(_) => state.copy(breakerOpen = true)
      case other            => onBreakerClosed(state, other)

  private def onBreakerClosed(state: State, evt: BreakerEvt): State =
    evt match
      case BreakerClosed => state.copy(breakerOpen = false)
      case _             => state

  private def onTreeEvent(state: State, evt: TreeEvt): State =
    evt match
      case MeeseeksAdded(mid) => state.copy(meeseeksIds = state.meeseeksIds + mid)
      case other              => onTreeRest(state, other)

  private def onTreeRest(state: State, evt: TreeEvt): State =
    evt match
      case ChildAdded(cid, pid) =>
        state.copy(
          childParent = state.childParent + (cid -> pid),
          meeseeksIds = state.meeseeksIds + cid
        )
      case other => onWorkSolved(state, other)

  private def onWorkSolved(state: State, evt: TreeEvt): State =
    evt match
      case WorkSolved(goal, result) =>
        state.copy(solvedWork = state.solvedWork + (goal -> result))
      case _ => state

end Overseer
