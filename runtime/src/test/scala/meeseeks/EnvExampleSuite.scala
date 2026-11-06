package meeseeks

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Verifies that .env.example at the repo root contains all required env var keys and that no key
  * holds a real secret value.
  *
  * Red step for issue #65: this test fails until .env.example is created.
  */
class EnvExampleSuite extends munit.FunSuite:

  // Walk up from the working directory until we find the directory that contains build.sbt.
  private def repoRoot: Path =
    val start = Paths.get("").toAbsolutePath
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(fail(s"could not locate repo root from $start"))

  test(".env.example exists at the repo root") {
    val path = repoRoot.resolve(".env.example")
    assert(Files.exists(path), s".env.example not found at $path")
  }

  test(".env.example contains all required env var keys") {
    val content = Files.readString(repoRoot.resolve(".env.example"))

    val required = Seq(
      "MEESEEKS_OWNER_ID",
      "MEESEEKS_TELEGRAM_BOT_TOKEN",
      "MEESEEKS_TELEGRAM_CHAT_ID",
      "MEESEEKS_JOURNAL_PATH",
      "MEESEEKS_WRITER_ID",
      "MEESEEKS_BRAIN"
    )

    required.foreach { key =>
      assert(content.contains(key), s"missing key: $key")
    }
  }

  test(".env.example has empty values for sensitive keys (no committed secrets)") {
    val content = Files.readString(repoRoot.resolve(".env.example"))

    // These keys must have empty values only; a real value is a leaked secret.
    val sensitiveKeys = Seq(
      "MEESEEKS_TELEGRAM_BOT_TOKEN",
      "MEESEEKS_OWNER_ID",
      "MEESEEKS_TELEGRAM_CHAT_ID",
      "MEESEEKS_BRAIN_API_KEY",
      "MEESEEKS_BRAIN_SUBSCRIPTION_CREDENTIAL",
      "MEESEEKS_JEV_API_KEY"
    )

    val violations = content.linesIterator
      .filter(line => sensitiveKeys.exists(k => line.startsWith(k + "=")))
      .filterNot(_.endsWith("="))
      .toSeq

    assert(violations.isEmpty, s"sensitive keys with non-empty values: $violations")
  }

end EnvExampleSuite
