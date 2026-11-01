package meeseeks

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.Effect
import org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior
import java.time.Instant

/** Old event format. CounterEventAdapter converts this to Counter.Incremented on read. */
final case class IncrementedV1(amount: Int) extends CborSerializable

/** Persistent counter that stores increment events in the journal. */
object Counter:

  sealed trait Command
  final case class Increment(replyTo: ActorRef[Int]) extends Command with CborSerializable
  final case class GetCount(replyTo: ActorRef[Int]) extends Command with CborSerializable

  sealed trait Event

  /** Current event format. Every event has writerId and timestamp set by the server. */
  final case class Incremented(amount: Int, writerId: String, timestamp: Instant)
      extends Event
      with CborSerializable

  final case class State(count: Int)

  def apply(id: String): Behavior[Command] =
    Behaviors.setup { ctx =>
      val writerId = ctx.system.settings.config.getString("meeseeks.writer-id")
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(id),
        emptyState = State(0),
        commandHandler = onCommand(writerId),
        eventHandler = onEvent
      )
    }

  private def onCommand(writerId: String)(state: State, cmd: Command): Effect[Event, State] =
    cmd match
      case Increment(replyTo) =>
        Effect
          .persist(Incremented(1, writerId, Instant.now()))
          .thenRun(s => replyTo ! s.count)
      case GetCount(replyTo) =>
        Effect.none.thenRun(s => replyTo ! s.count)

  // IncrementedV1 is handled by CounterEventAdapter before reaching this handler.
  private def onEvent(state: State, event: Event): State =
    event match
      case Incremented(n, _, _) => State(state.count + n)
