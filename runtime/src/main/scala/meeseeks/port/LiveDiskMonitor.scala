package meeseeks.port

import java.nio.file.Files
import java.nio.file.Path

/** Live DiskMonitor: reads usable bytes from the FileStore containing the given data path. */
final class LiveDiskMonitor(dataPath: Path) extends DiskMonitor:
  override def freeBytes(): Long = Files.getFileStore(dataPath).getUsableSpace
