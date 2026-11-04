package meeseeks

import meeseeks.fake.*
import meeseeks.port.BrainError
import meeseeks.port.OracleVerdict
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Adapter tests: success, timeout, and retry. No Kyo imports; only the adapter uses Kyo. */
class KyoAdapterSuite extends munit.FunSuite:

  test("step success returns Passed") {
    val brain = FakeBrain.withValues("ok result")
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val task = Task("goal", TaskType.Build, "check")
    val f = KyoStep.runStep(brain, oracle, task)
    assertEquals(Await.result(f, 5.seconds), StepResult.Passed("ok result"))
  }

  test("step timeout returns Failed") {
    val brain = FakeBrain.scripted(Script.after(500.millis, "slow result"))
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val task = Task("goal", TaskType.Build, "check")
    val config = StepConfig(timeout = 100.millis, maxRetries = 0)
    val f = KyoStep.runStep(brain, oracle, task, config)
    val result = Await.result(f, 2.seconds)
    result match
      case StepResult.Failed(_) => ()
      case other                => fail(s"expected Failed, got $other")
  }

  test("step retry on brain error then success") {
    val brain = FakeBrain.scripted(
      Script.fail(BrainError.Unavailable("busy")),
      Script.ok("ok result")
    )
    val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val task = Task("goal", TaskType.Build, "check")
    val config = StepConfig(timeout = 5.seconds, maxRetries = 1)
    val f = KyoStep.runStep(brain, oracle, task, config)
    assertEquals(Await.result(f, 5.seconds), StepResult.Passed("ok result"))
  }

end KyoAdapterSuite
