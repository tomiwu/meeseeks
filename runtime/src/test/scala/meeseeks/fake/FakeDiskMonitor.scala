package meeseeks.fake

import meeseeks.port.DiskMonitor

/** In-memory DiskMonitor fake. Returns the value set at construction; value can be changed. */
final class FakeDiskMonitor(@volatile private var value: Long) extends DiskMonitor:
  override def freeBytes(): Long = value
  def set(bytes: Long): Unit = value = bytes

object FakeDiskMonitor:
  def withFreeBytes(bytes: Long): FakeDiskMonitor = new FakeDiskMonitor(bytes)
  def gbFree(gb: Long): FakeDiskMonitor = withFreeBytes(gb * 1024L * 1024L * 1024L)
