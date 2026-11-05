package meeseeks.fake

import meeseeks.brain.BackendKind
import meeseeks.brain.BrainConfig
import meeseeks.brain.BrainRegistry
import meeseeks.port.Brain

/** In-memory BrainRegistry that maps each BackendKind to a scripted FakeBrain.
  *
  * Used in CI: covers all three backend kinds without any real credential or network call.
  */
final class FakeBrainRegistry(
    subscriptionBrain: Brain,
    apiKeyBrain: Brain,
    localModelBrain: Brain
) extends BrainRegistry:

  override def resolve(config: BrainConfig): Brain = config.kind match
    case BackendKind.Subscription => subscriptionBrain
    case BackendKind.ApiKey       => apiKeyBrain
    case BackendKind.LocalModel   => localModelBrain

object FakeBrainRegistry:

  /** All three backends return the same scripted response. Used when backend kind is irrelevant. */
  def forAllKinds(value: String): FakeBrainRegistry =
    new FakeBrainRegistry(
      FakeBrain.withValues(value),
      FakeBrain.withValues(value),
      FakeBrain.withValues(value)
    )
