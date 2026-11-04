package meeseeks.sandbox

import java.nio.file.Path

/** Configuration for the Podman sandbox.
  *
  * @param image      container image to run
  * @param taskDir    host path mounted read-only at workDir inside the container
  * @param credential optional single backend credential injected via env file
  * @param workDir    mount point inside the container
  */
final case class SandboxConfig(
    image: String,
    taskDir: Path,
    credential: Option[Credential] = None,
    workDir: String = "/workspace"
)
