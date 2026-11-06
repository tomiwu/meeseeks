package meeseeks

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The build, CI, and Sandcastle required checks use Scala 3.10.0 only.
  */
class ScalaVersionSuite extends munit.FunSuite:

  // Walk up from the working directory until we find the directory that contains build.sbt.
  private def repoRoot: Path =
    val start = Paths.get("").toAbsolutePath
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(fail(s"could not locate repo root from $start"))

  private def read(rel: String): String =
    Files.readString(repoRoot.resolve(rel))

  test("build.sbt uses Scala 3.10.0 and does not list Scala 3.9.0") {
    val content = read("build.sbt")
    assert(content.contains("""scalaVersion := "3.10.0""""))
    assert(content.contains("""crossScalaVersions := Seq("3.10.0")"""))
    assert(!content.contains("3.9.0"))
  }

  test("CI workflow tests only Scala 3.10.0") {
    val content = read(".github/workflows/ci.yml")
    assert(content.contains(""""3.10.0""""))
    assert(!content.contains("3.9.0"))
  }

  test("Sandcastle required checks omit Scala 3.9.0 and keep the other CI jobs") {
    val content = read(".sandcastle/main.mts")
    val required = Seq("format", "lint", "test (3.10.0)", "crap", "import-check")
    required.foreach { name =>
      assert(content.contains(s"\"$name\""), s"missing required check: $name")
    }
    assert(!content.contains("3.9.0"))
  }

  test("PRD NFR1 states Scala 3.10.0 only") {
    val nfr1 =
      read("docs/PRD.md").linesIterator.find(_.contains("NFR1.")).getOrElse(fail("NFR1 missing"))
    assert(nfr1.contains("Scala 3.10.0 only"))
    assert(!nfr1.contains("3.9.0"))
  }

  test("ARCHITECTURE states that CI tests Scala 3.10.0 only") {
    val content = read("docs/ARCHITECTURE.md")
    assert(content.contains("CI tests Scala 3.10.0 only"))
    assert(!content.contains("CI tests 3.10.0 and 3.9.0"))
  }

  test("DECISIONS.md records the Q41 update and keeps the old Q41 text") {
    val content = read("docs/DECISIONS.md")
    assert(content.contains("Q41 approved (t67u)"))
    assert(content.contains("Q41 update (2026-11-06"))
    assert(content.contains("Scala 3.10.0 only"))
  }

  test("README states Scala 3.10.0") {
    val content = read("README.md")
    assert(content.contains("Scala 3.10.0"))
  }

end ScalaVersionSuite
