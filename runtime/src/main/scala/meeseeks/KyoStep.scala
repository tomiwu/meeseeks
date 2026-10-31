package meeseeks

import kyo.*
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Adapter: run a Kyo IO effect as a Future so actors can use pipeToSelf. */
object KyoStep:
  def runToFuture[T](effect: T < IO)(using ExecutionContext): Future[T] =
    // IO.run strips the IO tag and returns T.
    // We wrap in Future so actors can use pipeToSelf without blocking.
    Future(IO.run(effect))
