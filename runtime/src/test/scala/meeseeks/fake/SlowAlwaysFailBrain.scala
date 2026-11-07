package meeseeks.fake

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import meeseeks.port.Brain
import meeseeks.port.BrainError

/** Brain fake that counts each call, blocks for a scripted delay, then fails. The latch opens at
  * the first call.
  */
final class SlowAlwaysFailBrain(
    val callCount: AtomicInteger,
    delayMs: Long,
    val firstCall: CountDownLatch = new CountDownLatch(1)
) extends Brain:
  override def ask(prompt: String): String < (Sync & Abort[BrainError]) =
    Sync.defer:
      val _ = callCount.incrementAndGet()
      firstCall.countDown()
      Thread.sleep(delayMs)
      Abort.fail(BrainError.Unavailable("slow always-fail brain"))
