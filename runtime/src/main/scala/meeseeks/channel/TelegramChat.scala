package meeseeks.channel

import kyo.*
import meeseeks.port.Chat
import meeseeks.port.ChatError
import meeseeks.port.ChatMessage

/** Chat adapter backed by the Telegram Bot API.
  *
  * receive() polls for the next update and returns it as a ChatMessage. If no updates are ready, it
  * retries. Kyo's Sync effect is trampolined so the recursion is stack-safe.
  *
  * send() delivers text to tomaszChatId. Only Tomasz sends tasks (FR1); the Gateway enforces the
  * owner check on the sender field of received messages.
  *
  * @param api
  *   injectable HTTP layer; use LiveTelegramApi in production, FakeTelegramApi in tests
  * @param tomaszChatId
  *   Telegram chat ID for outbound result messages
  */
final class TelegramChat(api: TelegramApi, tomaszChatId: Long) extends Chat:

  private var offset: Long = 0L
  private var buffer: List[TelegramUpdate] = Nil

  override def receive(): ChatMessage < (Sync & Abort[ChatError]) =
    poll()

  override def send(text: String): Unit < (Sync & Abort[ChatError]) =
    api.sendMessage(tomaszChatId, text)

  private def poll(): ChatMessage < (Sync & Abort[ChatError]) =
    buffer match
      case upd :: rest =>
        Sync.defer:
          buffer = rest
          ChatMessage(upd.fromId, upd.text)
      case Nil =>
        api.pollUpdates(offset).flatMap { updates =>
          updates match
            case Nil =>
              poll()
            case upd :: rest =>
              Sync.defer:
                offset = updates.last.updateId + 1
                buffer = rest
                ChatMessage(upd.fromId, upd.text)
        }
