package meeseeks.fake

import kyo.*
import meeseeks.port.Chat
import meeseeks.port.ChatError
import meeseeks.port.ChatMessage
import scala.collection.mutable

/** In-memory Chat fake. Dequeues one message per receive call. Collects sent messages. */
final class FakeChat(
  incoming: mutable.Queue[Script[ChatError, ChatMessage]],
  val sent: mutable.ListBuffer[String],
) extends Chat:
  override def receive(): ChatMessage < (Sync & Abort[ChatError]) =
    Sync.defer:
      if incoming.isEmpty then Abort.fail(ChatError.Disconnected("no scripted messages remain"))
      else
        val step = incoming.dequeue()
        step.delay.foreach(d => Thread.sleep(d.toMillis))
        step.response match
          case Right(msg) => msg
          case Left(error) => Abort.fail(error)

  override def send(text: String): Unit < (Sync & Abort[ChatError]) =
    Sync.defer(sent.append(text))

object FakeChat:
  def scripted(steps: Script[ChatError, ChatMessage]*): FakeChat =
    new FakeChat(mutable.Queue.from(steps), mutable.ListBuffer.empty)
  def withMessages(messages: ChatMessage*): FakeChat =
    scripted(messages.map(Script.ok[ChatError, ChatMessage])*)
  def withError(error: ChatError): FakeChat =
    scripted(Script.fail(error))
