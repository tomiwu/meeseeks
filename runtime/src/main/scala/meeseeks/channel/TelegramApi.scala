package meeseeks.channel

import kyo.*
import meeseeks.port.ChatError

/** Low-level Telegram HTTP operations. Inject a fake in tests; use LiveTelegramApi in production.
  */
trait TelegramApi:

  /** Returns all new updates whose update_id is at or above offset.
    *
    * Returns an empty list when no updates are ready. Callers retry until a message arrives.
    */
  def pollUpdates(offset: Long): List[TelegramUpdate] < (Sync & Abort[ChatError])

  /** Sends text to the given Telegram chat ID. */
  def sendMessage(chatId: Long, text: String): Unit < (Sync & Abort[ChatError])
