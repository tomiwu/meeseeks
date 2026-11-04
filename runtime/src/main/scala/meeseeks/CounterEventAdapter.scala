package meeseeks

import java.time.Instant
import org.apache.pekko.persistence.journal.EventAdapter
import org.apache.pekko.persistence.journal.EventSeq

/** Converts IncrementedV1 (old journal format, no writerId or timestamp) to the current
  * Counter.Incremented format. Passes Counter.Incremented events through unchanged.
  *
  * Pekko calls fromJournal after deserialization, before the actor event handler. Events written
  * with no adapter are stored as IncrementedV1 CBOR; this adapter upgrades them on read.
  */
final class CounterEventAdapter extends EventAdapter:

  override def manifest(event: Any): String = ""

  override def toJournal(event: Any): Any = event

  override def fromJournal(event: Any, manifest: String): EventSeq =
    event match
      case e: IncrementedV1 =>
        EventSeq.single(Counter.Incremented(e.amount, "migrated", Instant.EPOCH))
      case e => EventSeq.single(e)
