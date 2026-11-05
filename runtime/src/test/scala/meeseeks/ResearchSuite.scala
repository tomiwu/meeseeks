package meeseeks

import kyo.*
import meeseeks.fake.*
import meeseeks.port.*
import meeseeks.research.*

/** AT9: three independent fake copies, a verifier, and a blind judge make a report with claim
  * marks.
  */
class ResearchSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  test("AT9: research flow produces report with claim marks from three independent copies") {
    val brain1 = FakeBrain.withValues(
      "CLAIM: Scala is a JVM language | SOURCE: https://scala-lang.org/about"
    )
    val brain2 = FakeBrain.withValues(
      "CLAIM: Scala supports functional programming | SOURCE: https://scala-lang.org/fp"
    )
    val brain3 = FakeBrain.withValues(
      "CLAIM: Scala has a strong type system | SOURCE: https://scala-lang.org/types"
    )
    val registry = new FakeBrainRegistry(brain1, brain2, brain3)

    val verifier = FakeUrlVerifier.withStatuses(
      "https://scala-lang.org/about" -> UrlStatus.Supported,
      "https://scala-lang.org/fp" -> UrlStatus.NotSupported,
      "https://scala-lang.org/types" -> UrlStatus.Unreachable
    )

    val judge = FakeResearchJudge.withMarks(
      ClaimMark.Verified,
      ClaimMark.AgreedOnly,
      ClaimMark.Disputed
    )

    val task = Task("Scala overview", TaskType.Research, "must cover Scala features")

    val effect = ResearchCoordinator.run(task, registry, verifier, judge, copyCount = 3, seed = 0L)
    val result: Result[OracleError, ResearchReport] =
      Sync.Unsafe.evalOrThrow(Abort.run[OracleError](effect))

    result.fold(
      report => {
        assertEquals(report.markedClaims.length, 3)
        assert(report.markedClaims.forall(mc => mc.urlStatus != null))
        assert(report.markedClaims.forall(mc => mc.mark != null))
        val marks = report.markedClaims.map(_.mark).toSet
        assert(marks.contains(ClaimMark.Verified))
        assert(marks.contains(ClaimMark.AgreedOnly))
        assert(marks.contains(ClaimMark.Disputed))
        val statuses = report.markedClaims.map(_.urlStatus).toSet
        assert(statuses.contains(UrlStatus.Supported))
        assert(statuses.contains(UrlStatus.NotSupported))
        assert(statuses.contains(UrlStatus.Unreachable))
      },
      e => fail(s"unexpected oracle error: $e"),
      t => fail(s"unexpected panic: $t")
    )

    assertEquals(brain1.receivedPrompts.length, 1, "brain1 must be called once")
    assertEquals(brain2.receivedPrompts.length, 1, "brain2 must be called once")
    assertEquals(brain3.receivedPrompts.length, 1, "brain3 must be called once")

    assert(
      brain1.receivedPrompts.head != brain2.receivedPrompts.head,
      "copies must get different prompts"
    )
    assert(
      brain2.receivedPrompts.head != brain3.receivedPrompts.head,
      "copies must get different prompts"
    )

    val blindClaims = judge.receivedClaims.toList
    assertEquals(blindClaims.length, 3)
    blindClaims.foreach { bc =>
      assert(
        !bc.text.toLowerCase.contains("copy"),
        s"blind claim must not contain copy info: ${bc.text}"
      )
      assert(
        !bc.text.toLowerCase.contains("brain"),
        s"blind claim must not contain brain name: ${bc.text}"
      )
    }
  }

  test("claim parser extracts claims from brain response") {
    val response =
      "Some intro text\nCLAIM: Scala is functional | SOURCE: https://example.com/fp\nCLAIM: Scala runs on JVM | SOURCE: https://example.com/jvm\nEnd."
    val claims = ClaimParser.parse(response)
    assertEquals(claims.length, 2)
    assertEquals(claims(0), Claim("Scala is functional", "https://example.com/fp"))
    assertEquals(claims(1), Claim("Scala runs on JVM", "https://example.com/jvm"))
  }

  test("claim parser returns empty sequence for response with no claims") {
    val claims = ClaimParser.parse("No claims here, just prose.")
    assertEquals(claims.length, 0)
  }

end ResearchSuite
