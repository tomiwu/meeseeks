package meeseeks

import meeseeks.fake.CounterV1
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import scala.concurrent.duration.*

/** Proves: CounterEventAdapter converts IncrementedV1 (old format) to Incremented on recovery.
  *
  * Phase 1: CounterV1 writes IncrementedV1 events. The adapter's toJournal is identity, so the
  * events are stored as IncrementedV1 CBOR in H2. Phase 2: Counter recovers the same persistence
  * ID. CounterEventAdapter.fromJournal converts IncrementedV1 to Counter.Incremented before the
  * event handler runs.
  *
  * Without the adapter, phase 2 recovery fails: IncrementedV1 cannot be cast to Counter.Event.
  */
class JournalEventAdapterSuite extends munit.FunSuite:

  test("event adapter converts IncrementedV1 to Incremented on recovery") {
    val pid = "journal-adapter-test-1"

    // Phase 1: write old-format events. Fresh persistence ID; no recovery in this phase.
    val kit1 = ActorTestKit()
    val probe1 = kit1.createTestProbe[Int]()
    val v1 = kit1.spawn(CounterV1(pid), "cv1")
    v1 ! Counter.Increment(probe1.ref)
    assertEquals(probe1.receiveMessage(5.seconds), 1)
    v1 ! Counter.Increment(probe1.ref)
    assertEquals(probe1.receiveMessage(5.seconds), 2)
    kit1.stop(v1)
    kit1.shutdownTestKit()

    // Phase 2: new actor system, same H2 DB. Adapter must convert IncrementedV1 on recovery.
    val kit2 = ActorTestKit()
    val probe2 = kit2.createTestProbe[Int]()
    val counter2 = kit2.spawn(Counter(pid), "c2-recovered")
    counter2 ! Counter.GetCount(probe2.ref)
    assertEquals(probe2.receiveMessage(5.seconds), 2)
    kit2.shutdownTestKit()
  }

  test("Counter writes Incremented with writerId and recovers count after restart") {
    val pid = "journal-adapter-test-2"

    // Phase 1: write new-format events (with writerId and timestamp).
    val kit1 = ActorTestKit()
    val probe1 = kit1.createTestProbe[Int]()
    val counter1 = kit1.spawn(Counter(pid), "c1")
    counter1 ! Counter.Increment(probe1.ref)
    assertEquals(probe1.receiveMessage(5.seconds), 1)
    counter1 ! Counter.Increment(probe1.ref)
    assertEquals(probe1.receiveMessage(5.seconds), 2)
    kit1.stop(counter1)
    kit1.shutdownTestKit()

    // Phase 2: new actor system, same H2 DB; state must recover.
    val kit2 = ActorTestKit()
    val probe2 = kit2.createTestProbe[Int]()
    val counter2 = kit2.spawn(Counter(pid), "c1-recovered")
    counter2 ! Counter.GetCount(probe2.ref)
    assertEquals(probe2.receiveMessage(5.seconds), 2)
    kit2.shutdownTestKit()
  }
