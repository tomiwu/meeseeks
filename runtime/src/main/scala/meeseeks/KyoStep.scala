package meeseeks

import kyo.*
import meeseeks.port.Brain
import meeseeks.port.BrainError
import meeseeks.port.Oracle
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*

/** Result of one step: passed with output, or failed with reason. */
enum StepResult:
  case Passed(output: String)
  case Failed(reason: String)

/** Step configuration: timeout applied to the whole step, and maximum retry count. */
final case class StepConfig(
    timeout: FiniteDuration = 30.seconds,
    maxRetries: Int = 0
)

/** Adapter: runs one Meeseeks step (brain call + oracle check) as Kyo effects, returns a Future.
  *
  * Only this file imports Kyo. Actors see StepResult and Future only.
  */
object KyoStep:

  /** Run one brain-oracle step with retry and timeout.
    *
    * The returned Future always completes; a step failure becomes StepResult.Failed.
    */
  def runStep(
      brain: Brain,
      oracle: Oracle,
      task: Task,
      config: StepConfig = StepConfig()
  )(using ExecutionContext): Future[StepResult] =
    import AllowUnsafe.embrace.danger
    val effect: StepResult < Sync = runWithRetry(brain, oracle, task, config.maxRetries)
    val future: Future[StepResult] =
      Future(Sync.Unsafe.evalOrThrow(effect))
        .recover { case _ => StepResult.Failed("step execution error") }
    withTimeout(future, config.timeout)

  /** Run any Kyo Sync effect as a Future. Used for simple actor integration. */
  def runToFuture[T](effect: T < Sync)(using ExecutionContext): Future[T] =
    import AllowUnsafe.embrace.danger
    Future(Sync.Unsafe.evalOrThrow(effect))

  private def runOnce(
      brain: Brain,
      oracle: Oracle,
      task: Task
  ): StepResult < (Sync & Abort[BrainError] & Abort[OracleError]) =
    brain.ask(task.goal).flatMap { output =>
      oracle.judge(OracleInput(task.goal, output)).map {
        case OracleVerdict.Pass => StepResult.Passed(output)
        case other              => StepResult.Failed(other.toString)
      }
    }

  private def runWithRetry(
      brain: Brain,
      oracle: Oracle,
      task: Task,
      retriesLeft: Int
  ): StepResult < Sync =
    Abort.run[BrainError](Abort.run[OracleError](runOnce(brain, oracle, task))).flatMap { outer =>
      val resultOpt: Option[StepResult] = outer.fold(
        inner =>
          inner.fold(
            result => Some(result),
            _ => None,
            _ => Some(StepResult.Failed("oracle panic"))
          ),
        _ => None,
        _ => Some(StepResult.Failed("brain panic"))
      )
      val r: StepResult < Sync = resultOpt match
        case Some(result) => result
        case None =>
          if retriesLeft > 0 then runWithRetry(brain, oracle, task, retriesLeft - 1)
          else StepResult.Failed("step failed after retries")
      r
    }

  private def withTimeout(
      f: Future[StepResult],
      duration: FiniteDuration
  )(using ec: ExecutionContext): Future[StepResult] =
    val p = Promise[StepResult]()
    val timer = new java.util.Timer(true)
    val timerTask = new java.util.TimerTask:
      def run(): Unit =
        val _ = p.trySuccess(StepResult.Failed(s"step timed out after $duration"))
    timer.schedule(timerTask, duration.toMillis)
    f.onComplete { result =>
      timer.cancel()
      val _ = p.tryComplete(result)
    }
    p.future

end KyoStep
