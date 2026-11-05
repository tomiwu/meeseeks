package meeseeks.jev

import meeseeks.sandbox.Credential

/** Configuration for the Jev oracle first pass.
  *
  * @param enabled
  *   false by default; set true only when a credential is supplied
  * @param credential
  *   API key injected at runtime; never logged (Credential.toString shows only the name)
  * @param baseUrl
  *   Jev API base URL
  */
final case class JevConfig(
    enabled: Boolean,
    credential: Option[Credential] = None,
    baseUrl: String = "https://api.jev.ai"
)

object JevConfig:
  /** Default configuration: Jev is off. No credential is required. */
  val disabled: JevConfig = JevConfig(enabled = false)
