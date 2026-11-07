package meeseeks.brain

import kyo.*
import meeseeks.port.Brain
import meeseeks.port.BrainError
import meeseeks.port.Sandbox
import meeseeks.port.SandboxError
import meeseeks.port.SandboxResult
import meeseeks.sandbox.Credential
import meeseeks.sandbox.SandboxConfig

import java.nio.file.Files
import java.nio.file.Path

/** Brain that runs Claude Code (subscription CLI) inside a Podman sandbox.
  *
  * Per call:
  *   1. Create a fresh temp directory. 2. Write the prompt to brain-prompt.txt inside that
  *      directory. 3. Obtain a Sandbox from sandboxFactory(tempDir); the sandbox mounts that
  *      directory. 4. Run `sh -c "claude --print < /workspace/brain-prompt.txt"` via runArgs. 5.
  *      Delete the temp directory on completion.
  *
  * The optional credential is injected as a single env-file line; its value never appears in logs
  * because Credential.toString masks the secret.
  *
  * @param sandboxFactory
  *   given a temp directory, return a Sandbox that mounts it
  * @param image
  *   container image that has the Claude Code CLI installed
  * @param credential
  *   optional subscription credential injected via Podman env file
  * @param workDir
  *   mount point inside the container (default /workspace)
  */
final class SubscriptionBrain(
    sandboxFactory: (Path) => Sandbox,
    image: String,
    credential: Option[Credential] = None,
    workDir: String = "/workspace"
) extends Brain:

  override def ask(prompt: String): String < (Sync & Abort[BrainError]) =
    Sync.defer:
      import AllowUnsafe.embrace.danger
      val tempDir = Files.createTempDirectory("meeseeks-sub-")
      val promptFile = tempDir.resolve("brain-prompt.txt")
      try
        Files.writeString(promptFile, prompt)
        val sandbox = sandboxFactory(tempDir)
        val cmd = List("sh", "-c", s"claude --print < $workDir/brain-prompt.txt")
        val sandboxEffect = sandbox.runArgs(cmd)
        val result = Sync.Unsafe.evalOrThrow(Abort.run[SandboxError](sandboxEffect))
        result.fold(
          sr => mapResult(sr),
          err => Abort.fail(BrainError.Unavailable(s"sandbox error: ${err.toString}")),
          t => Abort.fail(BrainError.Unavailable(s"sandbox panic: ${t.getMessage}"))
        )
      catch
        case e: Exception =>
          Abort.fail(BrainError.Unavailable(s"subscription brain error: ${e.getMessage}"))
      finally
        try
          val _ = Files.deleteIfExists(promptFile)
          val _ = Files.deleteIfExists(tempDir)
        catch case _: Exception => ()

  private def mapResult(sr: SandboxResult): String < Abort[BrainError] =
    val combined = (sr.stdout + "\n" + sr.stderr).toLowerCase
    if isUsageLimit(combined) then
      Abort.fail(BrainError.UsageLimit(s"subscription usage limit: ${sr.stderr.trim}"))
    else mapAfterUsage(sr, combined)

  private def mapAfterUsage(sr: SandboxResult, combined: String): String < Abort[BrainError] =
    if isRateLimit(combined) then
      Abort.fail(BrainError.RateLimit(s"subscription rate limit: ${sr.stderr.trim}"))
    else mapAfterRate(sr)

  private def mapAfterRate(sr: SandboxResult): String < Abort[BrainError] =
    if sr.exitCode != 0 then
      Abort.fail(BrainError.Unavailable(s"claude exited ${sr.exitCode}: ${sr.stderr.trim}"))
    else mapOutput(sr)

  private def mapOutput(sr: SandboxResult): String < Abort[BrainError] =
    if sr.stdout.trim.isEmpty then
      Abort.fail(BrainError.Unavailable("subscription brain returned empty output"))
    else sr.stdout.trim

  private def isUsageLimit(combined: String): Boolean =
    hasUse(combined) || hasBill(combined)

  private def hasUse(combined: String): Boolean =
    combined.contains("usage limit") || combined.contains("monthly limit")

  private def hasBill(combined: String): Boolean =
    combined.contains("quota exceeded") || combined.contains("credit")

  private def isRateLimit(combined: String): Boolean =
    combined.contains("rate limit") || combined.contains("too many requests")

object SubscriptionBrain:

  /** Build a SubscriptionBrain backed by real Podman sandboxes.
    *
    * @param image
    *   container image with the Claude Code CLI
    * @param credential
    *   optional subscription credential
    * @param workDir
    *   container mount point
    */
  def live(
      image: String,
      credential: Option[Credential] = None,
      workDir: String = "/workspace"
  ): SubscriptionBrain =
    import meeseeks.sandbox.PodmanSandbox
    new SubscriptionBrain(
      sandboxFactory = taskDir =>
        new PodmanSandbox(SandboxConfig(image, taskDir, credential, workDir)),
      image = image,
      credential = credential,
      workDir = workDir
    )
