package meeseeks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import meeseeks.fake.*
import meeseeks.port.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.Terminated
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration.*

/** AT1 Kill switch end-to-end acceptance test.
  *
  * AT1a: after kill, 0 containers, 0 brain processes, no new iteration event. AT1b: workers confirm
  * the kill (actors terminate). AT1c: kill state survives a server restart.
  */
class KillSwitchSuite extends munit.FunSuite:

  override val munitTimeout: scala.concurrent.duration.Duration = 30.seconds

  /** Oracle that always returns Fail so the Meeseeks loop keeps running. */
  private object AlwaysFailOracle extends Oracle:
    override def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError]) =
      Sync.defer(OracleVerdict.Fail("not done"))

  // AT1a + AT1b: kill stops both Meeseeks actors; no new brain calls after kill.
  test("AT1a-b: kill stops all workers; no new brain call starts after kill") {
    val callCount = new AtomicInteger(0)
    val brain = new SlowAlwaysFailBrain(callCount, 200L)
    val kit = ActorTestKit()
    val sink = kit.createTestProbe[String]()

    val overseer = kit.spawn(Overseer("at1ab-os-1"), "at1ab-os-1")

    val m1 = kit.spawn(
      Meeseeks(
        "at1ab-m1",
        Task("goal1", TaskType.Build, "check"),
        overseer,
        brain,
        AlwaysFailOracle,
        sink.ref,
        permitRetryDelay = 50.millis,
        heartbeatInterval = 100.millis
      ),
      "at1ab-m1"
    )
    val m2 = kit.spawn(
      Meeseeks(
        "at1ab-m2",
        Task("goal2", TaskType.Build, "check"),
        overseer,
        brain,
        AlwaysFailOracle,
        sink.ref,
        permitRetryDelay = 50.millis,
        heartbeatInterval = 100.millis
      ),
      "at1ab-m2"
    )

    // AT1b: watch both actors; a CountDownLatch confirms they terminate.
    val latch = new CountDownLatch(2)
    val _ = kit.spawn(
      Behaviors.setup[Any] { ctx =>
        ctx.watch(m1)
        ctx.watch(m2)
        Behaviors.receiveSignal { case (_, _: Terminated) =>
          latch.countDown()
          Behaviors.same
        }
      },
      "at1ab-watcher"
    )

    // Wait for at least one brain call: proves the loop is running before kill.
    assert(
      brain.firstCall.await(5, TimeUnit.SECONDS),
      "expected at least one brain call before kill"
    )

    // Send the kill command.
    overseer ! Overseer.Kill

    // AT1b: workers confirm the kill by terminating.
    assert(latch.await(10, TimeUnit.SECONDS), "both workers must terminate after kill")

    // Brain counter was incremented at call start, before the sleep.
    // After both actors have stopped no new call can start.
    val countAfterKill = callCount.get()

    // AT1a: overseer denies all new permits (no new iteration event).
    val permitProbe = kit.createTestProbe[Overseer.PermitResult]()
    overseer ! Overseer.AskPermit("after-kill-check", permitProbe.ref)
    assertEquals(
      permitProbe.receiveMessage(5.seconds),
      Overseer.Denied,
      "AT1a: overseer must deny all permits after kill"
    )

    // Both workers are stopped, so the call count must not rise.
    assertEquals(
      callCount.get(),
      countAfterKill,
      "AT1a: 0 brain processes after kill — call count must not increase"
    )

    kit.shutdownTestKit()
  }

  // AT1c: kill state survives an overseer restart.
  test("AT1c: kill state survives overseer restart") {
    val pid = "at1c-os-1"

    // Phase 1: start overseer, send kill, confirm permit is denied, then stop.
    val kit1 = ActorTestKit()
    val overseer1 = kit1.spawn(Overseer(pid), "at1c-os1")
    overseer1 ! Overseer.Kill
    val permitProbe1 = kit1.createTestProbe[Overseer.PermitResult]()
    overseer1 ! Overseer.AskPermit("verify-killed", permitProbe1.ref)
    assertEquals(permitProbe1.receiveMessage(5.seconds), Overseer.Denied)
    kit1.stop(overseer1)
    kit1.shutdownTestKit()

    // Phase 2: recover with the same persistence ID in a fresh actor system.
    // The kill event is replayed from the journal; permit must still be denied.
    val kit2 = ActorTestKit()
    val overseer2 = kit2.spawn(Overseer(pid), "at1c-os2")
    val permitProbe2 = kit2.createTestProbe[Overseer.PermitResult]()
    overseer2 ! Overseer.AskPermit("after-restart-check", permitProbe2.ref)
    assertEquals(
      permitProbe2.receiveMessage(5.seconds),
      Overseer.Denied,
      "AT1c: kill must still be in effect after overseer restart"
    )
    kit2.shutdownTestKit()
  }

end KillSwitchSuite
