package meeseeks.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import meeseeks.CborSerializable

/** A content unit in an A2A message. Types: TextPart, FilePart, DataPart.
  *
  * The type annotations let Jackson read the right subtype when a message crosses the network.
  */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "partType")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[TextPart], name = "text"),
    new JsonSubTypes.Type(value = classOf[FilePart], name = "file"),
    new JsonSubTypes.Type(value = classOf[DataPart], name = "data")
  )
)
sealed trait Part extends CborSerializable

/** A text content part. */
final case class TextPart(text: String) extends Part

/** A named file content part. */
final case class FilePart(name: String, content: String) extends Part

/** A key-value data part. */
final case class DataPart(key: String, value: String) extends Part

/** A2A task state machine. Maps to Meeseeks task lifecycle (FR20). */
enum TaskState:
  case Submitted, Working, InputRequired, Completed, Failed, Canceled

/** One turn in an A2A task. Follows the A2A Message structure. */
final case class A2AMessage(role: String, parts: List[Part]) extends CborSerializable

/** A protocol message: an A2A task message with a bearer token (FR19, FR20).
  *
  * The server checks the token with verifyMessage before it processes any message.
  */
final case class A2AEnvelope(
    taskId: String,
    sessionId: String,
    message: A2AMessage,
    tokenId: String
) extends CborSerializable

/** Checks the bearer token in an envelope before the server processes the message.
  *
  * Returns the verified token or the reason the check failed. The server calls this before it
  * writes any event to the journal.
  */
def verifyMessage(
    envelope: A2AEnvelope,
    store: TokenStore
): Either[TokenError, ProcessToken] =
  store.verify(envelope.tokenId)
