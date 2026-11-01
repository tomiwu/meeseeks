package meeseeks

import kyo.*
import meeseeks.port.Chat
import meeseeks.port.ChatError

/** Reads one chat message and turns it into a Task, refuses it, or ignores it.
  *
  * Only messages from `ownerId` are processed. A message with no check is refused with one reply. A
  * message from any other sender is ignored with no reply.
  */
final class Gateway(ownerId: String, chat: Chat):

  def readTask(): Option[Task] < (Sync & Abort[ChatError]) =
    chat.receive().flatMap { msg =>
      if msg.sender != ownerId then (None: Option[Task])
      else
        Gateway.parse(msg.text) match
          case Some(task) => (Some(task): Option[Task])
          case None       => chat.send(Gateway.refusalText).map(_ => (None: Option[Task]))
    }

object Gateway:

  val refusalText: String =
    "Task refused: every task needs a check. " +
      "Add 'check: <command>' for build tasks or 'rubric: <text>' for research tasks."

  /** Parses a chat message text into a Task, or returns None if the check is missing. */
  def parse(text: String): Option[Task] =
    val fields = parseFields(text)
    for
      (goal, taskType) <-
        fields
          .get("build")
          .map(_ -> TaskType.Build)
          .orElse(fields.get("research").map(_ -> TaskType.Research))
      check <- fields.get("check").orElse(fields.get("rubric"))
    yield Task(goal, taskType, check)

  private def parseFields(text: String): Map[String, String] =
    text.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        line.split(":", 2).toList match
          case key :: value :: Nil => Some(key.trim.toLowerCase -> value.trim)
          case _                   => None
      }
      .toMap

end Gateway
