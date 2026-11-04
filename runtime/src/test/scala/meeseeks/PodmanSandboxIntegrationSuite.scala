package meeseeks

import kyo.*
import meeseeks.port.SandboxError
import meeseeks.port.SandboxResult
import meeseeks.sandbox.Credential
import meeseeks.sandbox.PodmanSandbox
import meeseeks.sandbox.SandboxConfig

import java.nio.file.Files

/** Local integration tests for PodmanSandbox. Skipped in CI.
  * Run locally: PODMAN_INTEGRATION=true sbt test
  */
class PodmanSandboxIntegrationSuite extends munit.FunSuite:

  override def munitIgnore: Boolean =
    sys.env.getOrElse("PODMAN_INTEGRATION", "false") != "true"

  import AllowUnsafe.embrace.danger

  private def runEffect[E: ConcreteTag, T](
      effect: T < (Sync & Abort[E])
  ): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  test("PodmanSandbox runs echo in a fresh read-only container") {
    val taskDir = Files.createTempDirectory("sandbox-it-")
    try
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir)
      val sandbox = PodmanSandbox(config)
      val result = runEffect(sandbox.run("echo hello"))
      result.fold(
        r => {
          assertEquals(r.exitCode, 0)
          assertEquals(r.stdout.trim, "hello")
        },
        e => fail(s"unexpected sandbox error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      val _ = taskDir.toFile.delete()
  }

  test("PodmanSandbox mounts task directory and runs script from it") {
    val taskDir = Files.createTempDirectory("sandbox-it-")
    try
      val script = taskDir.resolve("check.sh")
      val _ = Files.writeString(script, "#!/bin/sh\necho 'check passed'\nexit 0\n")
      val _ = script.toFile.setExecutable(true)
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir)
      val sandbox = PodmanSandbox(config)
      val result = runEffect(sandbox.run("sh /workspace/check.sh"))
      result.fold(
        r => {
          assertEquals(r.exitCode, 0)
          assert(r.stdout.contains("check passed"), s"stdout: '${r.stdout}'")
        },
        e => fail(s"unexpected sandbox error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      taskDir.toFile.listFiles().foreach(f => { val _ = f.delete() })
      val _ = taskDir.toFile.delete()
  }

  test("PodmanSandbox injects credential as an environment variable") {
    val taskDir = Files.createTempDirectory("sandbox-it-")
    try
      val script = taskDir.resolve("check-cred.sh")
      val _ = Files.writeString(script, "#!/bin/sh\necho $MY_API_KEY\n")
      val _ = script.toFile.setExecutable(true)
      val cred = Credential("MY_API_KEY", "test-secret-value")
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir, credential = Some(cred))
      val sandbox = PodmanSandbox(config)
      val result = runEffect(sandbox.run("sh /workspace/check-cred.sh"))
      result.fold(
        r => {
          assertEquals(r.exitCode, 0)
          assertEquals(r.stdout.trim, "test-secret-value")
        },
        e => fail(s"unexpected sandbox error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      taskDir.toFile.listFiles().foreach(f => { val _ = f.delete() })
      val _ = taskDir.toFile.delete()
  }

  test("PodmanSandbox container root filesystem is read-only") {
    val taskDir = Files.createTempDirectory("sandbox-it-")
    try
      val script = taskDir.resolve("write-test.sh")
      val _ = Files.writeString(script, "#!/bin/sh\ntouch /testfile\n")
      val _ = script.toFile.setExecutable(true)
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir)
      val sandbox = PodmanSandbox(config)
      val result = runEffect(sandbox.run("sh /workspace/write-test.sh"))
      result.fold(
        r => assertNotEquals(r.exitCode, 0, "expected non-zero exit for read-only filesystem"),
        e => fail(s"unexpected sandbox error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      taskDir.toFile.listFiles().foreach(f => { val _ = f.delete() })
      val _ = taskDir.toFile.delete()
  }

  test("PodmanSandbox starts a fresh container for each run") {
    val taskDir = Files.createTempDirectory("sandbox-it-")
    try
      val config = SandboxConfig(image = "alpine:latest", taskDir = taskDir)
      val sandbox = PodmanSandbox(config)
      val r1 = runEffect(sandbox.run("echo run1"))
      val r2 = runEffect(sandbox.run("echo run2"))
      r1.fold(
        r => assertEquals(r.stdout.trim, "run1"),
        e => fail(s"$e"),
        t => fail(s"$t")
      )
      r2.fold(
        r => assertEquals(r.stdout.trim, "run2"),
        e => fail(s"$e"),
        t => fail(s"$t")
      )
    finally
      val _ = taskDir.toFile.delete()
  }

end PodmanSandboxIntegrationSuite
