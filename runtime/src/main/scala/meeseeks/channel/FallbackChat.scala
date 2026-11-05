package meeseeks.channel

import kyo.*
import meeseeks.port.Chat
import meeseeks.port.ChatError
import meeseeks.port.ChatMessage

/** Chat adapter that tries primary first and falls back to secondary on Disconnected.
  *
  * Unauthorized errors propagate without fallback. Each call retries the primary first; there is
  * no permanent switch to secondary.
  */
final class FallbackChat(primary: Chat, secondary: Chat) extends Chat:

  override def receive(): ChatMessage < (Sync & Abort[ChatError]) =
    Abort.run[ChatError](primary.receive()).flatMap { result =>
      result.fold(
        msg => msg,
        { e =>
          e match
            case ChatError.Disconnected(_) => secondary.receive()
            case _                         => Abort.fail(e)
        },
        t => throw t
      )
    }

  override def send(text: String): Unit < (Sync & Abort[ChatError]) =
    Abort.run[ChatError](primary.send(text)).flatMap { result =>
      result.fold(
        _ => (),
        { e =>
          e match
            case ChatError.Disconnected(_) => secondary.send(text)
            case _                         => Abort.fail(e)
        },
        t => throw t
      )
    }
