package meeseeks

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The crap job runs the real crap4s tool with threshold 6.
  */
class CrapGateSuite extends munit.FunSuite:

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

  test("build.sbt keeps a real coverage gate and does not use +test") {
    val content = read("build.sbt")
    assert(!content.contains("addCommandAlias(\"crap\", \"+test\")"))
    assert(content.contains("coverageEnabled"))
    // A relative -coverage-out path is resolved from the sbt root, not from each module.
    assert(!content.contains("-coverage-out:target/coverage"))
  }

  test("CI crap job runs crap4s with threshold 6 on each module") {
    val content = read(".github/workflows/ci.yml")
    assert(content.contains("uses: tomiwu/crap4s@"))
    assert(content.contains("threshold: \"6\""))
    assert(content.contains("runtime/target/coverage"))
    assert(content.contains("dev/target/coverage"))
    assert(content.contains("run: sbt crap"))
  }

end CrapGateSuite
