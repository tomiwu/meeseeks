package meeseeks.fake

import meeseeks.Counter
import meeseeks.IncrementedV1
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.Effect
import org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior

/** Test helper: simulates the old server version that persisted IncrementedV1 events.
  * Used only in JournalEventAdapterSuite to prove adapter migration.
  */
object CounterV1:
  def apply(id: String): Behavior[Counter.Command] =
    EventSourcedBehavior[Counter.Command, IncrementedV1, Counter.State](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = Counter.State(0),
      commandHandler = onCommand,
      eventHandler = (state, e) => Counter.State(state.count + e.amount)
    )

  private def onCommand(
      state: Counter.State,
      cmd: Counter.Command
  ): Effect[IncrementedV1, Counter.State] =
    cmd match
      case Counter.Increment(ref) =>
        Effect.persist(IncrementedV1(1)).thenRun(s => ref ! s.count)
      case Counter.GetCount(ref) =>
        Effect.none.thenRun(s => ref ! s.count)
