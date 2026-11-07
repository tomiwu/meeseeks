package meeseeks.protocol

import meeseeks.CborSerializable
import meeseeks.port.WorkItem
import meeseeks.port.WorkResult
import org.apache.pekko.actor.typed.ActorRef

/** A request to run one work item on a worker node. The envelope carries the bearer token. */
final case class WorkRequest(envelope: A2AEnvelope, replyTo: ActorRef[WorkReply])
    extends CborSerializable

/** The answer of a worker node. The envelope repeats the task ID and the token ID of the request.
  *
  * The state is the name of a TaskState case. It is a string so the wire format stays simple.
  */
final case class WorkReply(envelope: A2AEnvelope, state: String) extends CborSerializable

private val sessionId = "work"

/** Build the request envelope for a work item. */
def workEnvelope(item: WorkItem, taskId: String, tokenId: String): A2AEnvelope =
  val parts = List(DataPart("goal", item.goal), DataPart("command", item.command))
  A2AEnvelope(taskId, sessionId, A2AMessage("user", parts), tokenId)

/** Read a work item from a request envelope. */
def readWorkItem(envelope: A2AEnvelope): Either[String, WorkItem] =
  val data = dataOf(envelope)
  for
    goal <- data.get("goal").toRight("missing goal")
    command <- data.get("command").toRight("missing command")
  yield WorkItem(goal, command)

/** Build the reply envelope for a finished work item. */
def resultReply(request: A2AEnvelope, result: WorkResult): WorkReply =
  val parts = List(DataPart("exitCode", result.exitCode.toString), TextPart(result.output))
  replyOf(request, TaskState.Completed, parts)

/** Build the reply envelope for a work item that could not run. */
def failureReply(request: A2AEnvelope, reason: String): WorkReply =
  replyOf(request, TaskState.Failed, List(TextPart(reason)))

/** Read the result from a reply. A failed reply gives the reason. */
def readWorkResult(reply: WorkReply): Either[String, WorkResult] =
  val parts = reply.envelope.message.parts
  val text = parts.collectFirst { case TextPart(t) => t }.getOrElse("")
  if reply.state == TaskState.Completed.toString then
    dataOf(reply.envelope)
      .get("exitCode")
      .flatMap(_.toIntOption)
      .map(WorkResult(_, text))
      .toRight("missing exit code")
  else Left(text)

private def replyOf(request: A2AEnvelope, state: TaskState, parts: List[Part]): WorkReply =
  val envelope = request.copy(message = A2AMessage("agent", parts))
  WorkReply(envelope, state.toString)

private def dataOf(envelope: A2AEnvelope): Map[String, String] =
  envelope.message.parts.collect { case DataPart(k, v) => k -> v }.toMap
