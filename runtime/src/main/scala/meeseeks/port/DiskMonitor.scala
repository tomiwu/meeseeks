package meeseeks.port

/** Port: returns the number of usable bytes on the data volume. */
trait DiskMonitor:
  def freeBytes(): Long
