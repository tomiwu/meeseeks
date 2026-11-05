package meeseeks

import kyo.*
import meeseeks.fake.*
import meeseeks.jev.*
import meeseeks.port.*
import meeseeks.sandbox.Credential

/** Tests for the Jev oracle (FR18, Q37, acceptance criteria of issue #15).
  *
  * Uses FakeJevClient as the fake Jev endpoint. No real network calls are made (CI uses fakes
  * only). Covers:
  *   - Off by default: fallback decides when Jev is disabled.
  *   - When on, unsure results go to the LLM judge (fallback).
  *   - API key comes only from a Credential (masked field).
  *   - Pass, Fail, Stop, Continue verdicts from Jev are returned directly.
  */
class JevOracleSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private def run[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  private val input = OracleInput("build the app", "all tests passed")

  // Off by default: JevConfig.disabled causes the fallback to be used.
  test("JevFirstOracle: disabled by default, fallback decides") {
    val jevClient = FakeJevClient.empty()
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val oracle = JevFirstOracle(JevConfig.disabled, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      v => assertEquals(v, OracleVerdict.Pass),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(jevClient.calls, List.empty, "Jev must not be called when disabled")
  }

  // When disabled, Jev is never called even if a client is wired.
  test("JevFirstOracle: disabled, Jev client is never called") {
    val jevClient = FakeJevClient.withVerdicts(OracleVerdict.Fail("should not appear"))
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Unsure("fallback unsure"))
    val oracle = JevFirstOracle(JevConfig.disabled, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      v => assertEquals(v, OracleVerdict.Unsure("fallback unsure")),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(jevClient.calls.length, 0, "Jev must not be called when disabled")
  }

  // When enabled and Jev returns Pass, the result is Pass without calling fallback.
  test("JevFirstOracle: enabled, Jev returns Pass, fallback not called") {
    val jevClient = FakeJevClient.withVerdicts(OracleVerdict.Pass)
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Fail("fallback must not run"))
    val config =
      JevConfig(enabled = true, credential = Some(Credential("JEV_API_KEY", "secret123")))
    val oracle = JevFirstOracle(config, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      v => assertEquals(v, OracleVerdict.Pass),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(jevClient.calls.length, 1, "Jev must be called once")
  }

  // When enabled and Jev returns Fail, the result is Fail without calling fallback.
  test("JevFirstOracle: enabled, Jev returns Fail, fallback not called") {
    val jevClient = FakeJevClient.withVerdicts(OracleVerdict.Fail("compilation error"))
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val config = JevConfig(enabled = true, credential = Some(Credential("JEV_API_KEY", "key")))
    val oracle = JevFirstOracle(config, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      v => assertEquals(v, OracleVerdict.Fail("compilation error")),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  // When enabled and Jev returns Unsure, the fallback decides (acceptance criterion).
  test("JevFirstOracle: enabled, Jev returns Unsure, fallback decides") {
    val jevClient = FakeJevClient.withVerdicts(OracleVerdict.Unsure("not sure"))
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val config = JevConfig(enabled = true, credential = Some(Credential("JEV_API_KEY", "key")))
    val oracle = JevFirstOracle(config, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      v => assertEquals(v, OracleVerdict.Pass),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
    assertEquals(jevClient.calls.length, 1, "Jev must have been called")
  }

  // When enabled and Jev returns Unsure, fallback returning Fail is used.
  test("JevFirstOracle: enabled, Jev Unsure then fallback Fail") {
    val jevClient = FakeJevClient.withVerdicts(OracleVerdict.Unsure("cannot decide"))
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Fail("fallback says fail"))
    val config = JevConfig(enabled = true, credential = Some(Credential("JEV_API_KEY", "key")))
    val oracle = JevFirstOracle(config, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      v => assertEquals(v, OracleVerdict.Fail("fallback says fail")),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  // When enabled and Jev returns Stop with highConfidence, Stop is returned directly.
  test("JevFirstOracle: enabled, Jev returns Stop with high confidence") {
    val jevClient =
      FakeJevClient.withVerdicts(OracleVerdict.Stop("impossible task", highConfidence = true))
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val config = JevConfig(enabled = true, credential = Some(Credential("JEV_API_KEY", "key")))
    val oracle = JevFirstOracle(config, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      {
        case OracleVerdict.Stop(reason, true) =>
          assertEquals(reason, "impossible task")
        case other => fail(s"expected Stop(highConfidence=true), got $other")
      },
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  // When enabled and Jev returns Continue, Continue is returned directly.
  test("JevFirstOracle: enabled, Jev returns Continue") {
    val jevClient = FakeJevClient.withVerdicts(OracleVerdict.Continue)
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val config = JevConfig(enabled = true, credential = Some(Credential("JEV_API_KEY", "key")))
    val oracle = JevFirstOracle(config, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      v => assertEquals(v, OracleVerdict.Continue),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  // When enabled and Jev returns an error, the error is propagated.
  test("JevFirstOracle: enabled, Jev error is propagated") {
    val jevClient = FakeJevClient.withError(OracleError.Unavailable("Jev is down"))
    val fallback = FakeOracle.withVerdicts(OracleVerdict.Pass)
    val config = JevConfig(enabled = true, credential = Some(Credential("JEV_API_KEY", "key")))
    val oracle = JevFirstOracle(config, jevClient, fallback)

    val result = run(oracle.judge(input))

    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case OracleError.Unavailable(msg) =>
          assert(msg.contains("Jev is down"), s"unexpected message: $msg")
        case other => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  // JevConfig.disabled has enabled=false with no credential.
  test("JevConfig.disabled: enabled is false, credential is None") {
    val config = JevConfig.disabled
    assertEquals(config.enabled, false)
    assertEquals(config.credential, None)
  }

  // Credential.toString does not reveal the secret (masked field requirement).
  test("Credential.toString masks the secret value") {
    val cred = Credential("JEV_API_KEY", "super-secret")
    assert(!cred.toString.contains("super-secret"), s"secret must not appear in: ${cred.toString}")
    assert(cred.toString.contains("JEV_API_KEY"), s"name must appear in: ${cred.toString}")
  }

  // FakeJevClient records every call it receives.
  test("FakeJevClient records received inputs") {
    val client = FakeJevClient.withVerdicts(OracleVerdict.Pass, OracleVerdict.Fail("x"))
    val in1 = OracleInput("goal1", "out1")
    val in2 = OracleInput("goal2", "out2")
    val _ = run(client.judge(in1))
    val _ = run(client.judge(in2))
    assertEquals(client.calls, List(in1, in2))
  }

end JevOracleSuite
