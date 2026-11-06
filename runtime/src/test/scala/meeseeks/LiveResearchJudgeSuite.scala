package meeseeks

import kyo.*
import meeseeks.fake.FakeBrain
import meeseeks.port.BrainError
import meeseeks.port.LiveResearchJudge
import meeseeks.port.OracleError
import meeseeks.research.BlindClaim
import meeseeks.research.ClaimMark
import meeseeks.research.UrlStatus

/** Unit tests for LiveResearchJudge. Uses FakeBrain — no paid API or external service. */
class LiveResearchJudgeSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private def run[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  private val sampleClaims = Seq(
    BlindClaim("Scala is a JVM language", "https://example.com/1", UrlStatus.Supported),
    BlindClaim("Scala is functional", "https://example.com/2", UrlStatus.NotSupported),
    BlindClaim("Scala has a type system", "https://example.com/3", UrlStatus.Unreachable)
  )

  test("LiveResearchJudge parses VERIFIED, AGREED_ONLY, DISPUTED from brain response") {
    val brain = FakeBrain.withValues("1: VERIFIED\n2: AGREED_ONLY\n3: DISPUTED")
    val judge = new LiveResearchJudge(brain)
    val result = run(judge.score(sampleClaims, "must cover Scala features"))
    result.fold(
      marks => {
        assertEquals(marks.length, 3)
        assertEquals(marks(0), ClaimMark.Verified)
        assertEquals(marks(1), ClaimMark.AgreedOnly)
        assertEquals(marks(2), ClaimMark.Disputed)
      },
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveResearchJudge sends a prompt that includes the rubric") {
    val brain = FakeBrain.withValues("1: VERIFIED\n2: VERIFIED\n3: VERIFIED")
    val judge = new LiveResearchJudge(brain)
    val _ = run(judge.score(sampleClaims, "must cover all Scala features"))
    val prompt = brain.receivedPrompts.head
    assert(prompt.contains("must cover all Scala features"), s"rubric not in prompt: $prompt")
  }

  test("LiveResearchJudge prompt does not contain writer ID or brain name") {
    val brain = FakeBrain.withValues("1: VERIFIED")
    val judge = new LiveResearchJudge(brain)
    val oneClaim =
      Seq(BlindClaim("Scala is functional", "https://example.com/1", UrlStatus.Supported))
    val _ = run(judge.score(oneClaim, "rubric"))
    val prompt = brain.receivedPrompts.head
    assert(
      !prompt.toLowerCase.contains("writerid"),
      s"prompt must not contain writerId: $prompt"
    )
    assert(
      !prompt.toLowerCase.contains("brainname"),
      s"prompt must not contain brain name: $prompt"
    )
  }

  test("LiveResearchJudge includes claim text in prompt") {
    val brain = FakeBrain.withValues("1: VERIFIED")
    val judge = new LiveResearchJudge(brain)
    val oneClaim =
      Seq(BlindClaim("Scala runs on JVM", "https://example.com/jvm", UrlStatus.Supported))
    val _ = run(judge.score(oneClaim, "rubric"))
    val prompt = brain.receivedPrompts.head
    assert(prompt.contains("Scala runs on JVM"), s"claim text must appear in prompt: $prompt")
  }

  test("LiveResearchJudge includes URL status in prompt") {
    val brain = FakeBrain.withValues("1: VERIFIED")
    val judge = new LiveResearchJudge(brain)
    val oneClaim =
      Seq(BlindClaim("claim text", "https://example.com", UrlStatus.Supported))
    val _ = run(judge.score(oneClaim, "rubric"))
    val prompt = brain.receivedPrompts.head
    assert(prompt.contains("supported"), s"URL status must appear in prompt: $prompt")
  }

  test("LiveResearchJudge defaults unrecognised mark to Disputed") {
    val brain = FakeBrain.withValues("1: UNKNOWN_MARK")
    val judge = new LiveResearchJudge(brain)
    val oneClaim =
      Seq(BlindClaim("claim", "https://example.com", UrlStatus.Supported))
    val result = run(judge.score(oneClaim, "rubric"))
    result.fold(
      marks => assertEquals(marks(0), ClaimMark.Disputed),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveResearchJudge fills missing marks with Disputed") {
    // Brain returns only one mark for two claims.
    val brain = FakeBrain.withValues("1: VERIFIED")
    val judge = new LiveResearchJudge(brain)
    val twoClaims = Seq(
      BlindClaim("claim 1", "https://example.com/1", UrlStatus.Supported),
      BlindClaim("claim 2", "https://example.com/2", UrlStatus.Supported)
    )
    val result = run(judge.score(twoClaims, "rubric"))
    result.fold(
      marks => {
        assertEquals(marks.length, 2)
        assertEquals(marks(0), ClaimMark.Verified)
        assertEquals(marks(1), ClaimMark.Disputed)
      },
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveResearchJudge maps brain BrainError to OracleError.Unavailable") {
    val brain = FakeBrain.withError(BrainError.Unavailable("brain down"))
    val judge = new LiveResearchJudge(brain)
    val result = run(judge.score(sampleClaims, "rubric"))
    result.fold(
      v => fail(s"expected error, got $v"),
      {
        case OracleError.Unavailable(_) => ()
        case other                      => fail(s"expected Unavailable, got $other")
      },
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveResearchJudge returns empty sequence for empty claims list") {
    val brain = FakeBrain.withValues("should not be called")
    val judge = new LiveResearchJudge(brain)
    val result = run(judge.score(Seq.empty, "rubric"))
    result.fold(
      marks => {
        assertEquals(marks.length, 0)
        assertEquals(brain.receivedPrompts.length, 0, "brain must not be called for empty claims")
      },
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

end LiveResearchJudgeSuite
