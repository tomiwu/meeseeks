package meeseeks

import kyo.*
import meeseeks.fake.*
import meeseeks.port.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import scala.concurrent.duration.*

/** Tests for the Meeseeks actor: permit loop, heartbeats, check pass, end-to-end flow. */
class MeeseeksSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  // AT4: one iteration, check passes, one result sent.
  test("single iteration check passes, result is sent") {
    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("step output")
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("build the app", TaskType.Build, "sbt test")
    val overseer = kit.spawn(Overseer("ms-single-os-1"), "ms-single-os-1")

    val _ = kit.spawn(
      Meeseeks("ms-single-1", task, overseer, brain, oracle, resultProbe.ref),
      "ms-single-1"
    )

    val result = resultProbe.receiveMessage(5.seconds)
    assertEquals(result, "Task complete: step output")

    kit.shutdownTestKit()
  }

  // Loop retries until check passes.
  test("loop retries until check passes, result sent after check passes") {
    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("output-1", "output-2", "output-3")
    val oracle = FakeOracle.withVerdicts(
      OracleVerdict.Fail("not done"),
      OracleVerdict.Fail("still not done"),
      OracleVerdict.Pass
    )
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("build the app", TaskType.Build, "sbt test")
    val overseer = kit.spawn(Overseer("ms-retry-os-1"), "ms-retry-os-1")

    val _ = kit.spawn(
      Meeseeks(
        "ms-retry-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        permitRetryDelay = 50.millis
      ),
      "ms-retry-1"
    )

    val result = resultProbe.receiveMessage(10.seconds)
    assertEquals(result, "Task complete: output-3")

    kit.shutdownTestKit()
  }

  // Heartbeats prevent stall detection from killing the Meeseeks.
  test("heartbeats are sent to overseer and prevent stall detection (AT8)") {
    val kit = ActorTestKit()
    // Slow brain so the heartbeat timeout can fire during the step.
    val brain = FakeBrain.scripted(Script.after(300.millis, "output"))
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("goal", TaskType.Build, "check")

    val overseer = kit.spawn(
      Overseer(
        "ms-hb-os-1",
        _ => (),
        heartbeatTimeout = 150.millis,
        checkInterval = 50.millis
      ),
      "ms-hb-os-1"
    )

    val _ = kit.spawn(
      Meeseeks(
        "ms-hb-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        heartbeatInterval = 50.millis
      ),
      "ms-hb-1"
    )

    // If heartbeats work the actor survives and delivers the result.
    // Without heartbeats the overseer would kill it at ~150 ms.
    val result = resultProbe.receiveMessage(5.seconds)
    assertEquals(result, "Task complete: output")

    kit.shutdownTestKit()
  }

  // No iteration starts without a permit.
  test("no iteration starts when kill denies all permits") {
    val kit = ActorTestKit()
    val brain = FakeBrain.withValues("output")
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val resultProbe = kit.createTestProbe[String]()
    val task = Task("goal", TaskType.Build, "check")

    val overseer = kit.spawn(Overseer("ms-noperm-os-1"), "ms-noperm-os-1")
    overseer ! Overseer.Kill

    val _ = kit.spawn(
      Meeseeks(
        "ms-noperm-1",
        task,
        overseer,
        brain,
        oracle,
        resultProbe.ref,
        permitRetryDelay = 100.millis
      ),
      "ms-noperm-1"
    )

    // Kill denies permits; no result should arrive.
    resultProbe.expectNoMessage(500.millis)

    kit.shutdownTestKit()
  }

  // End-to-end: gateway parses a chat task, Meeseeks runs, result is delivered.
  test("gateway task to result works end to end with fakes") {
    val kit = ActorTestKit()

    val taskMsg = ChatMessage("tomasz", "build: add feature X\ncheck: sbt test")
    val chat = FakeChat.withMessages(taskMsg)
    val gw = Gateway("tomasz", chat)

    val parseResult: Result[ChatError, Option[Task]] =
      Sync.Unsafe.evalOrThrow(Abort.run[ChatError](gw.readTask()))
    val task: Task = parseResult.fold(
      opt => opt.getOrElse(fail("gateway returned None; expected Some(task)")),
      e => fail(s"unexpected chat error: $e"),
      t => fail(s"unexpected panic: $t")
    )

    val brain = FakeBrain.withValues("feature X added, all tests pass")
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val resultProbe = kit.createTestProbe[String]()

    val overseer = kit.spawn(Overseer("ms-e2e-os-1"), "ms-e2e-os-1")
    val _ = kit.spawn(
      Meeseeks("ms-e2e-1", task, overseer, brain, oracle, resultProbe.ref),
      "ms-e2e-1"
    )

    val result = resultProbe.receiveMessage(5.seconds)
    assert(result.startsWith("Task complete:"), s"unexpected result: $result")

    kit.shutdownTestKit()
  }

end MeeseeksSuite
