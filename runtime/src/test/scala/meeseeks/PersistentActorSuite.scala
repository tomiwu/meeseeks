package meeseeks

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import scala.concurrent.duration.*

/** Proves: one persistent actor stores events in H2 via pekko-persistence-jdbc and recovers state
  * after an actor system restart.
  */
class PersistentActorSuite extends munit.FunSuite:

  test("counter persists events and recovers count after restart") {
    // Phase 1: write two increments into H2.
    val kit1 = ActorTestKit()
    val probe = kit1.createTestProbe[Int]()

    val counter = kit1.spawn(Counter("test-counter-1"), "counter1")
    counter ! Counter.Increment(probe.ref)
    assertEquals(probe.receiveMessage(5.seconds), 1)
    counter ! Counter.Increment(probe.ref)
    assertEquals(probe.receiveMessage(5.seconds), 2)

    kit1.stop(counter)
    kit1.shutdownTestKit()

    // Phase 2: new actor system, same DB, same persistence ID → state must recover.
    val kit2 = ActorTestKit()
    val probe2 = kit2.createTestProbe[Int]()
    val counter2 = kit2.spawn(Counter("test-counter-1"), "counter1-recovered")

    counter2 ! Counter.GetCount(probe2.ref)
    assertEquals(probe2.receiveMessage(5.seconds), 2)

    kit2.shutdownTestKit()
  }
