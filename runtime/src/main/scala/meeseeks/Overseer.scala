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

  /** A Meeseeks registers itself with its control ref. The overseer records the initial heartbeat.
    */
  final case class RegisterMeeseeks(id: String, ref: ActorRef[MeeseeksControl]) extends Command

  /** A child Meeseeks registers itself with its parent ID. */
  final case class RegisterChild(childId: String, parentId: String, ref: ActorRef[MeeseeksControl])
      extends Command

  /** A Meeseeks sends a periodic heartbeat to prove it is still running. */
  final case class Heartbeat(meeseeksId: String) extends Command

  /** A Meeseeks asks for a permit before its next loop iteration. */
  final case class AskPermit(meeseeksId: String, replyTo: ActorRef[PermitResult]) extends Command

  /** Stop all work. After this no permits are given. Workers receive a stop order. */
  case object Kill extends Command

  /** Open the circuit breaker with a reason. No permits until ResumeBreaker. */
  final case class OpenBreaker(reason: String) extends Command

  /** Close the circuit breaker. Permits resume. Only Tomasz sends this. */
  case object ResumeBreaker extends Command

  /** A Meeseeks at level 5 reports that it is stuck and cannot make progress. */
  final case class StuckReport(meeseeksId: String, reason: String) extends Command

  /** A child reports that one of its steps succeeded. Routes ChildSucceeded to the parent. */
  final case class ChildDone(childId: String, parentId: String, result: String) extends Command

  /** A child reports that one of its steps failed. Routes ChildFailed to the parent. */
  final case class ChildFail(childId: String, parentId: String) extends Command

  /** Query whether this goal has already been solved. */
  final case class QuerySolvedWork(goal: String, replyTo: ActorRef[SolvedWorkResult])
      extends Command

  /** Record a solved result so future Meeseeks can reuse it. */
  final case class RecordSolvedWork(goal: String, result: String) extends Command

  /** Return a snapshot of the Meeseeks tree for display. */
  final case class GetTreeView(replyTo: ActorRef[TreeView]) extends Command

  /** Internal timer message: check for Meeseeks actors that have missed their heartbeat. */
  private case object CheckStalls extends Command

  /** Internal timer message: check free disk space and open the breaker if below the threshold. */
  private case object CheckDisk extends Command

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

  /** All work was killed. No more permits. */
  case object AllKilled extends Event

  /** Circuit breaker opened due to a resource limit or error. */
  final case class BreakerOpened(reason: String) extends Event

  /** Circuit breaker closed. Permits resume. */
  case object BreakerClosed extends Event

  /** A Meeseeks was added to the tree for the first time. */
  final case class MeeseeksAdded(id: String) extends Event

  /** A child Meeseeks was added to the tree with its parent reference. */
  final case class ChildAdded(childId: String, parentId: String) extends Event

  /** A solved result was recorded for future reuse. */
  final case class WorkSolved(goal: String, result: String) extends Event

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
      // Ephemeral in-memory state: not in the journal.
      var refs = Map.empty[String, ActorRef[MeeseeksControl]]
      var heartbeats = Map.empty[String, Instant]

      timers.startTimerAtFixedRate(CheckStalls, checkInterval)
      diskMonitor.foreach { _ =>
        timers.startTimerAtFixedRate(CheckDisk, diskCheckInterval)
      }

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(id),
        emptyState = State(),
        commandHandler = { (state, cmd) =>
          cmd match
            case RegisterMeeseeks(mid, ref) =>
              refs = refs.updated(mid, ref)
              heartbeats = heartbeats.updated(mid, Instant.now())
              if state.meeseeksIds.contains(mid) then Effect.none
              else Effect.persist(MeeseeksAdded(mid))

            case RegisterChild(cid, pid, ref) =>
              refs = refs.updated(cid, ref)
              heartbeats = heartbeats.updated(cid, Instant.now())
              if state.childParent.contains(cid) then Effect.none
              else Effect.persist(ChildAdded(cid, pid))

            case Heartbeat(mid) =>
              heartbeats = heartbeats.updated(mid, Instant.now())
              Effect.none

            case AskPermit(_, replyTo) =>
              replyTo ! (if !state.killed && !state.breakerOpen then Granted else Denied)
              Effect.none

            case Kill =>
              if state.killed then Effect.none
              else
                Effect
                  .persist[Event, State](AllKilled)
                  .thenRun { _ => refs.values.foreach(_ ! StopMeeseeks) }

            case OpenBreaker(reason) =>
              if state.breakerOpen then Effect.none
              else
                Effect
                  .persist[Event, State](BreakerOpened(reason))
                  .thenRun { _ =>
                    notify(s"Circuit breaker opened: $reason. Send resume to restart.")
                  }

            case ResumeBreaker =>
              if !state.breakerOpen then Effect.none
              else Effect.persist(BreakerClosed)

            case StuckReport(mid, reason) =>
              notify(s"Meeseeks $mid is stuck: $reason. Waiting for upstream stop.")
              Effect.none

            case ChildDone(cid, pid, result) =>
              refs.get(pid).foreach(_ ! ChildSucceeded(cid, result))
              Effect.none

            case ChildFail(cid, pid) =>
              refs.get(pid).foreach(_ ! ChildFailed(cid))
              Effect.none

            case QuerySolvedWork(goal, replyTo) =>
              replyTo ! state.solvedWork
                .get(goal)
                .fold[SolvedWorkResult](NoSolvedWork)(SolvedWork.apply)
              Effect.none

            case RecordSolvedWork(goal, result) =>
              if state.solvedWork.contains(goal) then Effect.none
              else Effect.persist(WorkSolved(goal, result))

            case GetTreeView(replyTo) =>
              replyTo ! TreeView(state.meeseeksIds, state.childParent)
              Effect.none

            case CheckStalls =>
              val now = Instant.now()
              val stalled = heartbeats.filter { case (_, lastHb) =>
                java.time.Duration.between(lastHb, now).toMillis > heartbeatTimeout.toMillis
              }.keySet
              stalled.foreach { mid =>
                refs.get(mid).foreach(_ ! StopMeeseeks)
                refs = refs.removed(mid)
                heartbeats = heartbeats.removed(mid)
                factory(mid)
              }
              Effect.none

            case CheckDisk =>
              diskMonitor match
                case Some(dm) if !state.breakerOpen && dm.freeBytes() < DiskThresholdBytes =>
                  Effect
                    .persist[Event, State](BreakerOpened("free disk below 10 GB"))
                    .thenRun { _ =>
                      notify(
                        "Circuit breaker opened: free disk below 10 GB. Send resume to restart."
                      )
                    }
                case _ => Effect.none
        },
        eventHandler = { (state, event) =>
          event match
            case AllKilled        => state.copy(killed = true)
            case BreakerOpened(_) => state.copy(breakerOpen = true)
            case BreakerClosed    => state.copy(breakerOpen = false)
            case MeeseeksAdded(mid) =>
              state.copy(meeseeksIds = state.meeseeksIds + mid)
            case ChildAdded(cid, pid) =>
              state.copy(
                childParent = state.childParent + (cid -> pid),
                meeseeksIds = state.meeseeksIds + cid
              )
            case WorkSolved(goal, result) =>
              state.copy(solvedWork = state.solvedWork + (goal -> result))
        }
      )
    }

end Overseer
