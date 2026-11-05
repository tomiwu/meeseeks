package meeseeks.brain

import meeseeks.sandbox.Credential

/** Configuration for a brain backend.
  *
  * The credential field uses Credential whose toString masks the secret value. The case class
  * auto-generated toString is therefore safe to log.
  *
  * @param kind
  *   which backend kind to use
  * @param credential
  *   optional secret injected at runtime; never logged (Credential.toString shows only the name)
  */
final case class BrainConfig(
    kind: BackendKind,
    credential: Option[Credential] = None
)
