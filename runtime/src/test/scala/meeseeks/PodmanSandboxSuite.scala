package meeseeks

import meeseeks.sandbox.Credential
import meeseeks.sandbox.PodmanSandbox
import meeseeks.sandbox.SandboxConfig

import java.nio.file.Files

/** Unit tests for PodmanSandbox models. No Podman binary required. */
class PodmanSandboxSuite extends munit.FunSuite:

  test("Credential.toString does not reveal the secret value") {
    val cred = Credential("CLAUDE_KEY", "supersecret")
    assert(!cred.toString.contains("supersecret"))
    assert(cred.toString.contains("***"))
    assert(cred.toString.contains("CLAUDE_KEY"))
  }

  test("Credential.toEnvLine returns NAME=VALUE format") {
    val cred = Credential("API_KEY", "abc123")
    assertEquals(cred.toEnvLine, "API_KEY=abc123")
  }

  test("Credential equality is value-based") {
    val c1 = Credential("KEY", "val")
    val c2 = Credential("KEY", "val")
    val c3 = Credential("KEY", "other")
    assertEquals(c1, c2)
    assertNotEquals(c1, c3)
  }

  test("SandboxConfig holds image, taskDir, and optional credential") {
    val dir = Files.createTempDirectory("sandbox-unit-")
    try
      val config = SandboxConfig(image = "alpine:latest", taskDir = dir)
      assertEquals(config.image, "alpine:latest")
      assertEquals(config.taskDir, dir)
      assertEquals(config.credential, None)
      assertEquals(config.workDir, "/workspace")
    finally
      val _ = dir.toFile.delete()
  }

  test("SandboxConfig accepts a credential") {
    val dir = Files.createTempDirectory("sandbox-unit-")
    try
      val cred = Credential("KEY", "value")
      val config = SandboxConfig(image = "alpine:latest", taskDir = dir, credential = Some(cred))
      assertEquals(config.credential, Some(cred))
    finally
      val _ = dir.toFile.delete()
  }

end PodmanSandboxSuite
