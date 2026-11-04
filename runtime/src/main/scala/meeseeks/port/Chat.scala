package meeseeks.port

import kyo.*

final case class ChatMessage(sender: String, text: String)

enum ChatError:
  case Disconnected(message: String)
  case Unauthorized(message: String)

/** Port: receive messages from, and send messages to, a chat channel. */
trait Chat:
  def receive(): ChatMessage < (Sync & Abort[ChatError])
  def send(text: String): Unit < (Sync & Abort[ChatError])
