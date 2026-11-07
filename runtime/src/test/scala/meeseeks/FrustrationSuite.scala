package meeseeks

import meeseeks.fake.*
import meeseeks.port.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import scala.concurrent.duration.*

/** Tests for frustration level progression and level 5 behavior. */
class FrustrationSuite extends munit.FunSuite:

  // ---- Pure unit tests for FrustrationTracker ----

  test("level grows through all five stages as failed checks accumulate") {
    val config = FrustrationConfig(
      worriedAfterFails = 1,
      frustratedAfterFails = 2,
      desperateAfterFails = 3,
      stuckAfterFails = 4,
      worriedAfterMillis = Long.MaxValue,
      frustratedAfterMillis = Long.MaxValue,
      desperateAfterMillis = Long.MaxValue,
      stuckAfterMillis = Long.MaxValue
    )
    val clock = FakeClock(0L)
    val tracker = FrustrationTracker(config, clock)

    assertEquals(tracker.level, FrustrationLevel.Calm)
    tracker.recordFailure()
    assertEquals(tracker.level, FrustrationLevel.Worried)
    tracker.recordFailure()
    assertEquals(tracker.level, FrustrationLevel.Frustrated)
    tracker.recordFailure()
    assertEquals(tracker.level, FrustrationLevel.Desperate)
    tracker.recordFailure()
    assertEquals(tracker.level, FrustrationLevel.Stuck)
  }

  test("level grows through all five stages as time passes") {
    val config = FrustrationConfig(
      worriedAfterFails = Int.MaxValue,
      frustratedAfterFails = Int.MaxValue,
      desperateAfterFails = Int.MaxValue,
      stuckAfterFails = Int.MaxValue,
      worriedAfterMillis = 100L,
      frustratedAfterMillis = 200L,
      desperateAfterMillis = 300L,
      stuckAfterMillis = 400L
    )
    val clock = FakeClock(0L)
    val tracker = FrustrationTracker(config, clock)

    assertEquals(tracker.level, FrustrationLevel.Calm)
    clock.advance(100L)
    assertEquals(tracker.level, FrustrationLevel.Worried)
    clock.advance(100L)
    assertEquals(tracker.level, FrustrationLevel.Frustrated)
    clock.advance(100L)
    assertEquals(tracker.level, FrustrationLevel.Desperate)
    clock.advance(100L)
    assertEquals(tracker.level, FrustrationLevel.Stuck)
  }

  test("level reaches stuck when time threshold fires before fail threshold") {
    val config = FrustrationConfig(
      worriedAfterFails = Int.MaxValue,
      frustratedAfterFails = Int.MaxValue,
      desperateAfterFails = Int.MaxValue,
      stuckAfterFails = Int.MaxValue,
      worriedAfterMillis = Long.MaxValue,
      frustratedAfterMillis = Long.MaxValue,
      desperateAfterMillis = Long.MaxValue,
      stuckAfterMillis = 50L
    )
    val clock = FakeClock(0L)
    val tracker = FrustrationTracker(config, clock)

    assertEquals(tracker.level, FrustrationLevel.Calm)
    clock.advance(50L)
    assertEquals(tracker.level, FrustrationLevel.Stuck)
  }

  // ---- Actor tests for level 5 behavior ----

  /** Small FrustrationConfig used in actor tests: level 5 after 4 failed checks. */
  private val testFrustrationConfig = FrustrationConfig(
    worriedAfterFails = 1,
    frustratedAfterFails = 2,
    desperateAfterFails = 3,
    stuckAfterFails = 4,
    worriedAfterMillis = Long.MaxValue,
    frustratedAfterMillis = Long.MaxValue,
    desperateAfterMillis = Long.MaxValue,
    stuckAfterMillis = Long.MaxValue
  )

  // FR12: at level 5 the Meeseeks reports stuck exactly once, then keeps working.
  test("level 5 sends exactly one stuck report and loop continues to completion") {
    val stuckMessages = new java.util.concurrent.LinkedBlockingQueue[String]()

    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("v1", "v2", "v3", "v4", "v5", "v6", "v7")
    val oracle = FakeOracle.withVerdicts(
      OracleVerdict.Fail("not done"),
      OracleVerdict.Fail("not done"),
      OracleVerdict.Fail("not done"),
      OracleVerdict.Fail("not done"), // fail 4 -> level 5
      OracleVerdict.Fail("still going"), // already level 5, no second report
      OracleVerdict.Pass
    )
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("goal", TaskType.Build, "check")
    val overseer = kit.spawn(
      Overseer("fr-stuck-os-1", notify = stuckMessages.put),
      "fr-stuck-os-1"
    )

    val _ = kit.spawn(
      Meeseeks(
        "fr-stuck-m-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        frustrationConfig = testFrustrationConfig
      ),
      "fr-stuck-m-1"
    )

    // Stuck report must arrive.
    val msg = stuckMessages.poll(5, java.util.concurrent.TimeUnit.SECONDS)
    assertNotEquals(msg, null, "expected one stuck report to overseer")
    assert(msg.contains("fr-stuck-m-1"), s"report should name the Meeseeks: $msg")

    // Task completes after the oracle returns Pass.
    val result = resultProbe.receiveMessage(10.seconds)
    assert(result.startsWith("Task complete:"), s"unexpected result: $result")

    // No second stuck report.
    val second = stuckMessages.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    assertEquals(second, null, "expected only one stuck report, got a second one")

    kit.shutdownTestKit()
  }

  // FR12: upstream stop (Kill -> StopMeeseeks) ends the Meeseeks at level 5.
  test("upstream kill ends Meeseeks at level 5") {
    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("v1", "v2", "v3", "v4", "v5", "v6")
    // 4 fails to reach level 5, then oracle keeps returning Fail so it loops.
    val oracle = FakeOracle.withVerdicts(
      OracleVerdict.Fail("1"),
      OracleVerdict.Fail("2"),
      OracleVerdict.Fail("3"),
      OracleVerdict.Fail("4"),
      OracleVerdict.Fail("5"),
      OracleVerdict.Fail("6")
    )
    val stuckMessages = new java.util.concurrent.LinkedBlockingQueue[String]()
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("goal", TaskType.Build, "check")
    val overseer = kit.spawn(
      Overseer("fr-kill-os-1", notify = stuckMessages.put),
      "fr-kill-os-1"
    )

    val meeseeks = kit.spawn(
      Meeseeks(
        "fr-kill-m-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        permitRetryDelay = 50.millis,
        frustrationConfig = testFrustrationConfig
      ),
      "fr-kill-m-1"
    )

    // Wait for the stuck report: proves the Meeseeks reached level 5.
    assertNotEquals(
      stuckMessages.poll(5, java.util.concurrent.TimeUnit.SECONDS),
      null,
      "expected a stuck report at level 5"
    )

    // Upstream kill sends StopMeeseeks.
    overseer ! Overseer.Kill

    // No result should be sent to resultSink after kill.
    resultProbe.expectNoMessage(500.millis)

    kit.shutdownTestKit()
  }

  // FR12: low-confidence oracle Stop does NOT stop the Meeseeks at level 5.
  test("low-confidence oracle stop is ignored at level 5 and loop continues") {
    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("v1", "v2", "v3", "v4", "v5", "v6")
    val oracle = FakeOracle.withVerdicts(
      OracleVerdict.Fail("1"),
      OracleVerdict.Fail("2"),
      OracleVerdict.Fail("3"),
      OracleVerdict.Fail("4"), // -> level 5
      OracleVerdict.Stop("give up", false), // low confidence: ignored
      OracleVerdict.Pass
    )
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("goal", TaskType.Build, "check")
    val overseer = kit.spawn(Overseer("fr-lowconf-os-1"), "fr-lowconf-os-1")

    val _ = kit.spawn(
      Meeseeks(
        "fr-lowconf-m-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        frustrationConfig = testFrustrationConfig
      ),
      "fr-lowconf-m-1"
    )

    // Low-confidence stop at level 5 is ignored; task eventually completes.
    val result = resultProbe.receiveMessage(10.seconds)
    assert(result.startsWith("Task complete:"), s"unexpected result: $result")

    kit.shutdownTestKit()
  }

  // FR12: high-confidence oracle Stop ends the Meeseeks at level 5.
  test("high-confidence oracle stop ends Meeseeks at level 5") {
    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("v1", "v2", "v3", "v4", "v5")
    val oracle = FakeOracle.withVerdicts(
      OracleVerdict.Fail("1"),
      OracleVerdict.Fail("2"),
      OracleVerdict.Fail("3"),
      OracleVerdict.Fail("4"), // -> level 5
      OracleVerdict.Stop("forced stop", true) // high confidence: obey
    )
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("goal", TaskType.Build, "check")
    val overseer = kit.spawn(Overseer("fr-highconf-os-1"), "fr-highconf-os-1")

    val _ = kit.spawn(
      Meeseeks(
        "fr-highconf-m-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        frustrationConfig = testFrustrationConfig
      ),
      "fr-highconf-m-1"
    )

    // High-confidence stop at level 5 stops the actor.
    val result = resultProbe.receiveMessage(10.seconds)
    assert(result.startsWith("Task stopped:"), s"unexpected result: $result")

    kit.shutdownTestKit()
  }

  // Oracle Stop below level 5 always ends the Meeseeks (confidence does not matter).
  test("oracle stop below level 5 ends Meeseeks regardless of confidence") {
    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("v1", "v2")
    val oracle = FakeOracle.withVerdicts(
      OracleVerdict.Fail("1"), // fail 1 -> Worried
      OracleVerdict.Stop("early stop", false) // level 2, low confidence: still obeyed
    )
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("goal", TaskType.Build, "check")
    val overseer = kit.spawn(Overseer("fr-belowstuck-os-1"), "fr-belowstuck-os-1")

    val _ = kit.spawn(
      Meeseeks(
        "fr-belowstuck-m-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        frustrationConfig = testFrustrationConfig
      ),
      "fr-belowstuck-m-1"
    )

    val result = resultProbe.receiveMessage(10.seconds)
    assert(result.startsWith("Task stopped:"), s"unexpected result: $result")

    kit.shutdownTestKit()
  }

end FrustrationSuite
