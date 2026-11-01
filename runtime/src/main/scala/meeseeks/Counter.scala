package meeseeks

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.Effect
import org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior

/** Persistent counter that stores increment events in the journal. */
object Counter:

  sealed trait Command
  final case class Increment(replyTo: ActorRef[Int]) extends Command with CborSerializable
  final case class GetCount(replyTo: ActorRef[Int]) extends Command with CborSerializable

  sealed trait Event
  final case class Incremented(amount: Int) extends Event with CborSerializable

  final case class State(count: Int)

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = State(0),
      commandHandler = onCommand,
      eventHandler = onEvent
    )

  private def onCommand(state: State, cmd: Command): Effect[Event, State] =
    cmd match
      case Increment(replyTo) =>
        Effect.persist(Incremented(1)).thenRun(s => replyTo ! s.count)
      case GetCount(replyTo) =>
        Effect.none.thenRun(s => replyTo ! s.count)

  private def onEvent(state: State, event: Event): State =
    event match
      case Incremented(n) => State(state.count + n)
