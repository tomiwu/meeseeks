package meeseeks

import meeseeks.fake.FakeDiskMonitor
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import scala.concurrent.duration.*

/** Tests for the Overseer actor.
  *
  * Covers: permit gate (AT6), kill state, circuit breaker, stall detection (AT8), and state
  * recovery after restart (AT10). All tests use fakes and an in-memory H2 journal.
  */
class OverseerSuite extends munit.FunSuite:

  test("permit is granted when not killed and breaker is closed") {
    val kit = ActorTestKit()
    val probe = kit.createTestProbe[Overseer.PermitResult]()
    val overseer = kit.spawn(Overseer("os-permit-1"), "os-permit-1")

    overseer ! Overseer.AskPermit("m1", probe.ref)
    assertEquals(probe.receiveMessage(5.seconds), Overseer.Granted)

    kit.shutdownTestKit()
  }

  test("permit is denied after Kill") {
    val kit = ActorTestKit()
    val probe = kit.createTestProbe[Overseer.PermitResult]()
    val overseer = kit.spawn(Overseer("os-kill-1"), "os-kill-1")

    // Kill is processed first; AskPermit is stashed during persistence and runs after.
    overseer ! Overseer.Kill
    overseer ! Overseer.AskPermit("m1", probe.ref)
    assertEquals(probe.receiveMessage(5.seconds), Overseer.Denied)

    kit.shutdownTestKit()
  }

  test("permit is denied when circuit breaker is open") {
    val kit = ActorTestKit()
    val probe = kit.createTestProbe[Overseer.PermitResult]()
    val overseer = kit.spawn(Overseer("os-breaker-open-1"), "os-breaker-open-1")

    overseer ! Overseer.OpenBreaker("disk full")
    overseer ! Overseer.AskPermit("m1", probe.ref)
    assertEquals(probe.receiveMessage(5.seconds), Overseer.Denied)

    kit.shutdownTestKit()
  }

  test("permit is granted after ResumeBreaker closes the circuit breaker") {
    val kit = ActorTestKit()
    val probe = kit.createTestProbe[Overseer.PermitResult]()
    val overseer = kit.spawn(Overseer("os-resume-1"), "os-resume-1")

    overseer ! Overseer.OpenBreaker("disk full")
    overseer ! Overseer.ResumeBreaker
    overseer ! Overseer.AskPermit("m1", probe.ref)
    assertEquals(probe.receiveMessage(5.seconds), Overseer.Granted)

    kit.shutdownTestKit()
  }

  test("kill sends StopMeeseeks to all registered Meeseeks") {
    val kit = ActorTestKit()
    val controlProbe = kit.createTestProbe[Overseer.MeeseeksControl]()
    val overseer = kit.spawn(Overseer("os-kill-stop-1"), "os-kill-stop-1")

    overseer ! Overseer.RegisterMeeseeks("m-stop", controlProbe.ref)
    overseer ! Overseer.Kill
    controlProbe.expectMessage(5.seconds, Overseer.StopMeeseeks)

    kit.shutdownTestKit()
  }

  // AT8: missing heartbeats cause kill then restart.
  test("stalled Meeseeks is stopped and restarted when heartbeats stop") {
    val factoryProbe = new java.util.concurrent.LinkedBlockingQueue[String]()
    val factory: Overseer.MeeseeksFactory = id => factoryProbe.put(id)

    val kit = ActorTestKit()
    val controlProbe = kit.createTestProbe[Overseer.MeeseeksControl]()
    val overseer = kit.spawn(
      Overseer(
        "os-stall-1",
        factory,
        heartbeatTimeout = 200.millis,
        checkInterval = 50.millis
      ),
      "os-stall-1"
    )

    // Register but send no further heartbeats; the initial registration records one heartbeat.
    overseer ! Overseer.RegisterMeeseeks("m-stall", controlProbe.ref)

    // Within heartbeatTimeout + checkInterval the stall is detected.
    controlProbe.expectMessage(3.seconds, Overseer.StopMeeseeks)

    // Factory must be called to restart the Meeseeks.
    val restartedId = factoryProbe.poll(2, java.util.concurrent.TimeUnit.SECONDS)
    assertEquals(restartedId, "m-stall")

    kit.shutdownTestKit()
  }

  // AT10: kill state survives an actor system restart.
  test("kill state survives an actor system restart") {
    val pid = "os-restart-kill-1"

    val kit1 = ActorTestKit()
    val probe1 = kit1.createTestProbe[Overseer.PermitResult]()
    val overseer1 = kit1.spawn(Overseer(pid), "os-rk1")
    overseer1 ! Overseer.Kill
    // AskPermit stashed during kill persistence; sees killed state.
    overseer1 ! Overseer.AskPermit("m1", probe1.ref)
    assertEquals(probe1.receiveMessage(5.seconds), Overseer.Denied)
    kit1.stop(overseer1)
    kit1.shutdownTestKit()

    val kit2 = ActorTestKit()
    val probe2 = kit2.createTestProbe[Overseer.PermitResult]()
    val overseer2 = kit2.spawn(Overseer(pid), "os-rk2")
    overseer2 ! Overseer.AskPermit("m1", probe2.ref)
    assertEquals(probe2.receiveMessage(5.seconds), Overseer.Denied)
    kit2.shutdownTestKit()
  }

  // AT10: breaker state survives an actor system restart.
  test("breaker open state survives an actor system restart") {
    val pid = "os-restart-breaker-1"

    val kit1 = ActorTestKit()
    val probe1 = kit1.createTestProbe[Overseer.PermitResult]()
    val overseer1 = kit1.spawn(Overseer(pid), "os-rb1")
    overseer1 ! Overseer.OpenBreaker("test")
    overseer1 ! Overseer.AskPermit("m1", probe1.ref)
    assertEquals(probe1.receiveMessage(5.seconds), Overseer.Denied)
    kit1.stop(overseer1)
    kit1.shutdownTestKit()

    val kit2 = ActorTestKit()
    val probe2 = kit2.createTestProbe[Overseer.PermitResult]()
    val overseer2 = kit2.spawn(Overseer(pid), "os-rb2")
    overseer2 ! Overseer.AskPermit("m1", probe2.ref)
    assertEquals(probe2.receiveMessage(5.seconds), Overseer.Denied)
    kit2.shutdownTestKit()
  }

  // AT7: free disk below 10 GB opens the circuit breaker.
  test("AT7: free disk below 10 GB opens the circuit breaker") {
    val notified = new java.util.concurrent.CountDownLatch(1)
    val disk = FakeDiskMonitor.gbFree(9L)

    val kit = ActorTestKit()
    val permitProbe = kit.createTestProbe[Overseer.PermitResult]()

    val overseer = kit.spawn(
      Overseer(
        "at7-os-1",
        notify = _ => notified.countDown(),
        diskMonitor = Some(disk),
        diskCheckInterval = 50.millis
      ),
      "at7-os-1"
    )

    // Wait for disk check to trigger the breaker.
    assert(notified.await(5, java.util.concurrent.TimeUnit.SECONDS), "breaker did not open")

    // Permit is denied while breaker is open.
    overseer ! Overseer.AskPermit("test", permitProbe.ref)
    assertEquals(permitProbe.receiveMessage(5.seconds), Overseer.Denied)

    kit.shutdownTestKit()
  }

end OverseerSuite
