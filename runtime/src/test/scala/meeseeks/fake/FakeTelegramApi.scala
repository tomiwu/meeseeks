package meeseeks.fake

import kyo.*
import meeseeks.channel.TelegramApi
import meeseeks.channel.TelegramUpdate
import meeseeks.port.ChatError
import scala.collection.mutable

/** In-memory TelegramApi fake. Dequeues one scripted batch of updates per poll call. Collects sent
  * messages.
  *
  * Returns ChatError.Disconnected when the queue is empty.
  */
final class FakeTelegramApi(
    updates: mutable.Queue[Script[ChatError, List[TelegramUpdate]]],
    val sentMessages: mutable.ListBuffer[(Long, String)]
) extends TelegramApi:

  override def pollUpdates(offset: Long): List[TelegramUpdate] < (Sync & Abort[ChatError]) =
    Sync.defer:
      if updates.isEmpty then Abort.fail(ChatError.Disconnected("no scripted updates remain"))
      else
        val step = updates.dequeue()
        step.delay.foreach(d => Thread.sleep(d.toMillis))
        step.response match
          case Right(upds) => upds
          case Left(err)   => Abort.fail(err)

  override def sendMessage(chatId: Long, text: String): Unit < (Sync & Abort[ChatError]) =
    Sync.defer(sentMessages.append((chatId, text)))

object FakeTelegramApi:

  def withBatches(batches: List[TelegramUpdate]*): FakeTelegramApi =
    new FakeTelegramApi(
      mutable.Queue.from(batches.map(Script.ok[ChatError, List[TelegramUpdate]])),
      mutable.ListBuffer.empty
    )

  def withError(err: ChatError): FakeTelegramApi =
    new FakeTelegramApi(
      mutable.Queue(Script.fail[ChatError, List[TelegramUpdate]](err)),
      mutable.ListBuffer.empty
    )
