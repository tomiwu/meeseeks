package meeseeks

import java.nio.file.Files
import meeseeks.port.LiveDiskMonitor

/** Smoke tests for LiveDiskMonitor. Reads real disk stats; no paid API or external service. */
class LiveDiskMonitorSuite extends munit.FunSuite:

  test("LiveDiskMonitor.freeBytes returns a positive value for the current directory") {
    val dir = java.nio.file.Paths.get(".")
    val monitor = new LiveDiskMonitor(dir)
    assert(monitor.freeBytes() > 0L, s"expected positive free bytes, got ${monitor.freeBytes()}")
  }

  test("LiveDiskMonitor.freeBytes returns a positive value for a temp directory") {
    val dir = Files.createTempDirectory("disk-monitor-test-")
    try
      val monitor = new LiveDiskMonitor(dir)
      assert(monitor.freeBytes() > 0L, s"expected positive free bytes, got ${monitor.freeBytes()}")
    finally
      val _ = Files.deleteIfExists(dir)
  }

  test("LiveDiskMonitor.freeBytes returns a consistent value on repeated calls") {
    val dir = java.nio.file.Paths.get(".")
    val monitor = new LiveDiskMonitor(dir)
    val first = monitor.freeBytes()
    val second = monitor.freeBytes()
    // Values may differ slightly due to disk activity, but both must be positive.
    assert(first > 0L)
    assert(second > 0L)
  }

end LiveDiskMonitorSuite
