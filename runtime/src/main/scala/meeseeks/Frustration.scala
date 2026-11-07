package meeseeks

import scala.concurrent.duration.*

/** Frustration level of a Meeseeks actor. Grows with failed checks and elapsed time. */
enum FrustrationLevel(val value: Int):
  case Calm extends FrustrationLevel(1)
  case Worried extends FrustrationLevel(2)
  case Frustrated extends FrustrationLevel(3)
  case Desperate extends FrustrationLevel(4)
  case Stuck extends FrustrationLevel(5)

/** Thresholds and display names for the five frustration levels. All limits are configurable. */
final case class FrustrationConfig(
    worriedAfterFails: Int = 2,
    frustratedAfterFails: Int = 5,
    desperateAfterFails: Int = 9,
    stuckAfterFails: Int = 14,
    worriedAfterMillis: Long = 60_000L,
    frustratedAfterMillis: Long = 300_000L,
    desperateAfterMillis: Long = 900_000L,
    stuckAfterMillis: Long = 1_800_000L,
    levelNames: Map[FrustrationLevel, String] = FrustrationConfig.defaultNames
)

object FrustrationConfig:
  val defaultNames: Map[FrustrationLevel, String] = Map(
    FrustrationLevel.Calm -> "calm",
    FrustrationLevel.Worried -> "worried",
    FrustrationLevel.Frustrated -> "frustrated",
    FrustrationLevel.Desperate -> "desperate",
    FrustrationLevel.Stuck -> "stuck"
  )

/** Testable time source. The system clock is the default. */
trait Clock:
  def currentTimeMillis(): Long

object Clock:
  val system: Clock = new Clock:
    def currentTimeMillis(): Long = System.currentTimeMillis()

/** Tracks failed checks and elapsed time to compute the current frustration level. */
final class FrustrationTracker(config: FrustrationConfig, clock: Clock):
  private var failedChecks: Int = 0
  private val startMillis: Long = clock.currentTimeMillis()

  def recordFailure(): Unit = failedChecks += 1

  def failCount: Int = failedChecks

  def level: FrustrationLevel =
    val elapsed = clock.currentTimeMillis() - startMillis
    thresholds
      .find { case (failLimit, msLimit, _) => failedChecks >= failLimit || elapsed >= msLimit }
      .fold(FrustrationLevel.Calm)(_._3)

  private val thresholds: List[(Int, Long, FrustrationLevel)] = List(
    (config.stuckAfterFails, config.stuckAfterMillis, FrustrationLevel.Stuck),
    (config.desperateAfterFails, config.desperateAfterMillis, FrustrationLevel.Desperate),
    (config.frustratedAfterFails, config.frustratedAfterMillis, FrustrationLevel.Frustrated),
    (config.worriedAfterFails, config.worriedAfterMillis, FrustrationLevel.Worried)
  )
