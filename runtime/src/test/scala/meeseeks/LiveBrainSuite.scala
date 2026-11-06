package meeseeks

import kyo.*
import meeseeks.brain.*
import meeseeks.fake.*
import meeseeks.port.BrainError
import meeseeks.port.SandboxResult
import meeseeks.sandbox.Credential
import java.nio.file.Files

/** Tests for the three live brain backends and the live registry (issue #63).
  *
  * Uses FakeSandbox / FakeHttpSender — no real API calls or Podman in CI.
  *
  * AC: live registry resolves all three BackendKind values; Subscription uses PodmanSandbox; ApiKey
  * and LocalModel never log the secret; usage-limit errors become BrainError.UsageLimit.
  */
class LiveBrainSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private def run[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  // ── SubscriptionBrain ───────────────────────────────────────────────────

  test("SubscriptionBrain returns stdout on exit code 0") {
    val sandbox = FakeSandbox.withResults(SandboxResult(0, "brain reply", ""))
    val tmpDir = Files.createTempDirectory("test-sub-brain-")
    try
      val brain = new SubscriptionBrain(_ => sandbox, "test-image")
      val result = run(brain.ask("solve it"))
      result.fold(
        v => assertEquals(v, "brain reply"),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally
      tmpDir.toFile.listFiles().foreach(_.delete())
      val _ = Files.deleteIfExists(tmpDir)
  }

  test("SubscriptionBrain maps non-zero exit to Unavailable") {
    val sandbox = FakeSandbox.withResults(SandboxResult(1, "", "process error"))
    val brain = new SubscriptionBrain(_ => sandbox, "test-image")
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.Unavailable(msg) =>
          assert(msg.contains("1"), s"expected exit code in: $msg")
        case other => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("SubscriptionBrain maps 'rate limit' in output to RateLimit") {
    val sandbox =
      FakeSandbox.withResults(SandboxResult(0, "Claude AI Error: rate limit exceeded", ""))
    val brain = new SubscriptionBrain(_ => sandbox, "test-image")
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.RateLimit(_) => ()
        case other                   => fail(s"expected RateLimit, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("SubscriptionBrain maps 'usage limit' in output to UsageLimit") {
    val sandbox =
      FakeSandbox.withResults(SandboxResult(0, "Error: usage limit reached for this month", ""))
    val brain = new SubscriptionBrain(_ => sandbox, "test-image")
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.UsageLimit(_) => ()
        case other                    => fail(s"expected UsageLimit, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("SubscriptionBrain maps SandboxError to Unavailable") {
    val sandbox = FakeSandbox.withError(meeseeks.port.SandboxError.Unavailable("podman not found"))
    val brain = new SubscriptionBrain(_ => sandbox, "test-image")
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.Unavailable(_) => ()
        case other                     => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  // ── ApiKeyBrain ─────────────────────────────────────────────────────────

  private val apiSuccessJson =
    """{"content":[{"type":"text","text":"api response"}],"stop_reason":"end_turn"}"""

  test("ApiKeyBrain returns text content on HTTP 200") {
    val sender = FakeHttpSender.withResponses((200, apiSuccessJson))
    val brain = new ApiKeyBrain(Credential("MEESEEKS_BRAIN_API_KEY", "sk-test"), sender)
    val result = run(brain.ask("test prompt"))
    result.fold(
      v => assertEquals(v, "api response"),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("ApiKeyBrain sends API key in x-api-key header, not in body") {
    val secret = "sk-secret-key"
    val sender = FakeHttpSender.withResponses((200, apiSuccessJson))
    val brain = new ApiKeyBrain(Credential("MEESEEKS_BRAIN_API_KEY", secret), sender)
    val _ = run(brain.ask("prompt"))
    val (url, headers, body) = sender.calls.head
    assert(headers.get("x-api-key").contains(secret), "x-api-key header must carry the key")
    assert(!body.contains(secret), "API key must not appear in request body")
    assert(url.contains("anthropic.com"), s"URL must point to Anthropic: $url")
  }

  test("ApiKeyBrain does not expose key in toString") {
    val secret = "sk-super-secret"
    val cred = Credential("MEESEEKS_BRAIN_API_KEY", secret)
    assert(
      !cred.toString.contains(secret),
      s"secret found in Credential.toString: ${cred.toString}"
    )
  }

  test("ApiKeyBrain maps HTTP 429 to RateLimit") {
    val errorJson = """{"error":{"type":"rate_limit_error","message":"too many requests"}}"""
    val sender = FakeHttpSender.withResponses((429, errorJson))
    val brain = new ApiKeyBrain(Credential("MEESEEKS_BRAIN_API_KEY", "k"), sender)
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.RateLimit(_) => ()
        case other                   => fail(s"expected RateLimit, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("ApiKeyBrain maps usage_limit_error to UsageLimit") {
    val errorJson = """{"error":{"type":"usage_limit_error","message":"monthly limit reached"}}"""
    val sender = FakeHttpSender.withResponses((429, errorJson))
    val brain = new ApiKeyBrain(Credential("MEESEEKS_BRAIN_API_KEY", "k"), sender)
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.UsageLimit(_) => ()
        case other                    => fail(s"expected UsageLimit, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("ApiKeyBrain maps HTTP 500 to Unavailable") {
    val sender = FakeHttpSender.withResponses((500, "internal error"))
    val brain = new ApiKeyBrain(Credential("MEESEEKS_BRAIN_API_KEY", "k"), sender)
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.Unavailable(_) => ()
        case other                     => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("ApiKeyBrain maps sender error to BrainError") {
    val sender = FakeHttpSender.withError(BrainError.Unavailable("network error"))
    val brain = new ApiKeyBrain(Credential("MEESEEKS_BRAIN_API_KEY", "k"), sender)
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.Unavailable(_) => ()
        case other                     => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  // ── LocalModelBrain ──────────────────────────────────────────────────────

  private val openAiSuccessJson =
    """{"choices":[{"message":{"role":"assistant","content":"local reply"}}]}"""

  test("LocalModelBrain returns content on HTTP 200") {
    val sender = FakeHttpSender.withResponses((200, openAiSuccessJson))
    val brain = new LocalModelBrain("http://localhost:11434", "llama3.2", sender)
    val result = run(brain.ask("test"))
    result.fold(
      v => assertEquals(v, "local reply"),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LocalModelBrain sends request to configured host") {
    val sender = FakeHttpSender.withResponses((200, openAiSuccessJson))
    val brain = new LocalModelBrain("http://my-ollama:11434", "phi3", sender)
    val _ = run(brain.ask("prompt"))
    val (url, _, body) = sender.calls.head
    assert(url.startsWith("http://my-ollama:11434"), s"URL must use configured host: $url")
    assert(body.contains("phi3"), s"model name must appear in request body: $body")
  }

  test("LocalModelBrain maps HTTP 429 to RateLimit") {
    val sender = FakeHttpSender.withResponses((429, """{"error":"rate limited"}"""))
    val brain = new LocalModelBrain("http://localhost:11434", "llama3.2", sender)
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.RateLimit(_) => ()
        case other                   => fail(s"expected RateLimit, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LocalModelBrain maps HTTP 503 to Unavailable") {
    val sender = FakeHttpSender.withResponses((503, "service unavailable"))
    val brain = new LocalModelBrain("http://localhost:11434", "llama3.2", sender)
    val result = run(brain.ask("prompt"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.Unavailable(_) => ()
        case other                     => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  // ── LiveBrainRegistry ────────────────────────────────────────────────────

  test("LiveBrainRegistry resolves Subscription backend") {
    val subBrain = FakeBrain.withValues("from subscription")
    val registry = new LiveBrainRegistry(subBrain, None, None)
    val brain =
      registry.resolve(meeseeks.brain.BrainConfig(meeseeks.brain.BackendKind.Subscription))
    val result = run(brain.ask("p"))
    result.fold(
      v => assertEquals(v, "from subscription"),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveBrainRegistry resolves ApiKey backend when configured") {
    val apiBrain = FakeBrain.withValues("from api key")
    val registry = new LiveBrainRegistry(
      FakeBrain.withValues("unused"),
      Some(apiBrain),
      None
    )
    val brain = registry.resolve(meeseeks.brain.BrainConfig(meeseeks.brain.BackendKind.ApiKey))
    val result = run(brain.ask("p"))
    result.fold(
      v => assertEquals(v, "from api key"),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveBrainRegistry resolves LocalModel backend when configured") {
    val localBrain = FakeBrain.withValues("from local model")
    val registry = new LiveBrainRegistry(FakeBrain.withValues("unused"), None, Some(localBrain))
    val brain = registry.resolve(meeseeks.brain.BrainConfig(meeseeks.brain.BackendKind.LocalModel))
    val result = run(brain.ask("p"))
    result.fold(
      v => assertEquals(v, "from local model"),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveBrainRegistry returns Unavailable when ApiKey backend not configured") {
    val registry = new LiveBrainRegistry(FakeBrain.withValues("unused"), None, None)
    val brain = registry.resolve(meeseeks.brain.BrainConfig(meeseeks.brain.BackendKind.ApiKey))
    val result = run(brain.ask("p"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.Unavailable(_) => ()
        case other                     => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveBrainRegistry returns Unavailable when LocalModel backend not configured") {
    val registry = new LiveBrainRegistry(FakeBrain.withValues("unused"), None, None)
    val brain = registry.resolve(meeseeks.brain.BrainConfig(meeseeks.brain.BackendKind.LocalModel))
    val result = run(brain.ask("p"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case BrainError.Unavailable(_) => ()
        case other                     => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

end LiveBrainSuite
