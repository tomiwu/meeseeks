package meeseeks.port

import kyo.*

final case class WorkItem(goal: String, command: String)
final case class WorkResult(exitCode: Int, output: String)

enum WorkerError:
  case Overloaded(message: String)
  case Unreachable(message: String)
  case Failed(message: String)

/** Port: submit work to a remote worker node. */
trait Worker:
  def submit(item: WorkItem): WorkResult < (Sync & Abort[WorkerError])
