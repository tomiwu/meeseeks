package meeseeks.fake

import meeseeks.Clock

/** Fake clock for tests. Starts at a given time and advances manually. */
final class FakeClock(var time: Long = 0L) extends Clock:
  def currentTimeMillis(): Long = time
  def advance(millis: Long): Unit = time += millis
