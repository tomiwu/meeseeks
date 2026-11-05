package meeseeks

import kyo.*
import meeseeks.brain.BackendKind
import meeseeks.brain.BrainConfig
import meeseeks.brain.BrainRegistry
import meeseeks.fake.FakeBrain
import meeseeks.fake.FakeBrainRegistry
import meeseeks.port.BrainError
import meeseeks.sandbox.Credential

/** Tests for pluggable backend selection and secret redaction (issue #13).
  *
  * AC1: three backend kinds selectable by setting.
  * AC2: no secret in repo, journal, or logs.
  * AC3: fakes cover all three kinds in CI.
  */
class BackendSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private def run[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  // AC1: three backend kinds are selectable by setting.

  test("BackendKind has Subscription, ApiKey, and LocalModel") {
    val kinds = BackendKind.values.toSeq
    assert(kinds.contains(BackendKind.Subscription))
    assert(kinds.contains(BackendKind.ApiKey))
    assert(kinds.contains(BackendKind.LocalModel))
  }

  test("BrainConfig selects Subscription kind") {
    val config = BrainConfig(BackendKind.Subscription)
    assertEquals(config.kind, BackendKind.Subscription)
    assertEquals(config.credential, None)
  }

  test("BrainConfig selects ApiKey kind with credential") {
    val cred = Credential("CLAUDE_API_KEY", "sk-secret")
    val config = BrainConfig(BackendKind.ApiKey, Some(cred))
    assertEquals(config.kind, BackendKind.ApiKey)
    assertEquals(config.credential, Some(cred))
  }

  test("BrainConfig selects LocalModel kind without credential") {
    val config = BrainConfig(BackendKind.LocalModel)
    assertEquals(config.kind, BackendKind.LocalModel)
    assertEquals(config.credential, None)
  }

  // AC2: no secret in repo, journal, or logs (test checks log output via toString).

  test("Credential toString masks secret") {
    val secret = "very-secret-key"
    val cred = Credential("API_KEY", secret)
    assert(!cred.toString.contains(secret), s"secret found in: ${cred.toString}")
  }

  test("BrainConfig toString does not expose ApiKey secret") {
    val secret = "sk-super-secret-key"
    val cred = Credential("CLAUDE_API_KEY", secret)
    val config = BrainConfig(BackendKind.ApiKey, Some(cred))
    assert(!config.toString.contains(secret), s"secret found in: ${config.toString}")
  }

  test("BrainConfig toString does not expose Subscription secret") {
    val secret = "my-subscription-token"
    val cred = Credential("SUBSCRIPTION_TOKEN", secret)
    val config = BrainConfig(BackendKind.Subscription, Some(cred))
    assert(!config.toString.contains(secret), s"secret found in: ${config.toString}")
  }

  // AC3: fakes cover all three kinds in CI.

  test("FakeBrainRegistry resolves Subscription backend") {
    val registry = FakeBrainRegistry.forAllKinds("subscription response")
    val brain = registry.resolve(BrainConfig(BackendKind.Subscription))
    val result = run(brain.ask("prompt"))
    result.fold(
      v => assertEquals(v, "subscription response"),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("FakeBrainRegistry resolves ApiKey backend") {
    val registry = FakeBrainRegistry.forAllKinds("api key response")
    val cred = Credential("KEY", "secret")
    val brain = registry.resolve(BrainConfig(BackendKind.ApiKey, Some(cred)))
    val result = run(brain.ask("prompt"))
    result.fold(
      v => assertEquals(v, "api key response"),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("FakeBrainRegistry resolves LocalModel backend") {
    val registry = FakeBrainRegistry.forAllKinds("local model response")
    val brain = registry.resolve(BrainConfig(BackendKind.LocalModel))
    val result = run(brain.ask("prompt"))
    result.fold(
      v => assertEquals(v, "local model response"),
      e => fail(s"unexpected failure: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("FakeBrainRegistry routes each kind to its own Brain instance") {
    val subBrain = FakeBrain.withValues("from subscription")
    val apiBrain = FakeBrain.withValues("from api key")
    val localBrain = FakeBrain.withValues("from local model")
    val registry = FakeBrainRegistry(subBrain, apiBrain, localBrain)

    val r1 = run(registry.resolve(BrainConfig(BackendKind.Subscription)).ask("p"))
    val r2 = run(registry.resolve(BrainConfig(BackendKind.ApiKey)).ask("p"))
    val r3 = run(registry.resolve(BrainConfig(BackendKind.LocalModel)).ask("p"))

    r1.fold(v => assertEquals(v, "from subscription"), e => fail(s"$e"), t => fail(s"$t"))
    r2.fold(v => assertEquals(v, "from api key"), e => fail(s"$e"), t => fail(s"$t"))
    r3.fold(v => assertEquals(v, "from local model"), e => fail(s"$e"), t => fail(s"$t"))
  }

end BackendSuite
