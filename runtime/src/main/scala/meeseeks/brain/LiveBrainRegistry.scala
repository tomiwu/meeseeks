package meeseeks.brain

import kyo.*
import meeseeks.port.Brain
import meeseeks.port.BrainError

/** Live BrainRegistry: resolves all three BackendKind values.
  *
  * @param subscriptionBrain
  *   default brain; always required (Subscription is the production default)
  * @param apiKeyBrain
  *   optional; wire when MEESEEKS_BRAIN_API_KEY is set
  * @param localModelBrain
  *   optional; wire for offline use or CI smoke runs
  */
final class LiveBrainRegistry(
    subscriptionBrain: Brain,
    apiKeyBrain: Option[Brain],
    localModelBrain: Option[Brain]
) extends BrainRegistry:

  override def resolve(config: BrainConfig): Brain = config.kind match
    case BackendKind.Subscription =>
      subscriptionBrain
    case BackendKind.ApiKey =>
      apiKeyBrain.getOrElse(notConfigured("ApiKey", "MEESEEKS_BRAIN_API_KEY"))
    case BackendKind.LocalModel =>
      localModelBrain.getOrElse(notConfigured("LocalModel", "MEESEEKS_LOCAL_MODEL_HOST"))

  private def notConfigured(kind: String, envVar: String): Brain =
    new Brain:
      def ask(prompt: String): String < (Sync & Abort[BrainError]) =
        Abort.fail(
          BrainError.Unavailable(s"$kind backend not configured; set $envVar to enable it")
        )
