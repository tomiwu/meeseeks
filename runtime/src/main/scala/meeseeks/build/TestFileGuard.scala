package meeseeks.build

import java.nio.file.Path

/** Records the content of test files at construction, then detects changes by the code writer.
  *
  * A change to any registered test file is a fail (AT5).
  */
final class TestFileGuard(testPaths: Set[Path]):

  private val baseline: Map[Path, Option[String]] = captureSnapshot()

  private def captureSnapshot(): Map[Path, Option[String]] =
    import java.nio.file.Files
    testPaths.map { p =>
      p -> (if Files.exists(p) then Some(Files.readString(p)) else None)
    }.toMap

  /** Returns Some(reason) if any test file was added, removed, or modified since construction. */
  def detectChange(): Option[String] =
    import java.nio.file.Files
    testPaths.view.flatMap { p =>
      val current: Option[String] = if Files.exists(p) then Some(Files.readString(p)) else None
      (baseline(p), current) match
        case (None, Some(_))                                     => Some(s"test file added: $p")
        case (Some(_), None)                                     => Some(s"test file removed: $p")
        case (Some(before), Some(after)) if before != after      => Some(s"test file modified: $p")
        case _                                                   => None
    }.headOption

end TestFileGuard
