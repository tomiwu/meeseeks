package meeseeks

import kyo.*
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Adapter: run a Kyo Sync effect as a Future so actors can use pipeToSelf. */
object KyoStep:
  def runToFuture[T](effect: T < Sync)(using ExecutionContext): Future[T] =
    import AllowUnsafe.embrace.danger
    Future(Sync.Unsafe.evalOrThrow(effect))
