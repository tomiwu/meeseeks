package meeseeks.channel

/** One incoming message update from the Telegram Bot API (message subset only).
  *
  * @param updateId
  *   Telegram update_id; used to advance the polling offset
  * @param fromId
  *   Telegram user ID of the sender (string form matches ChatMessage.sender)
  * @param chatId
  *   Telegram chat ID; used for outbound replies
  * @param text
  *   message text
  */
final case class TelegramUpdate(
    updateId: Long,
    fromId: String,
    chatId: Long,
    text: String
)
