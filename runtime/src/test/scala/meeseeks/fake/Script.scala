package meeseeks.fake

import scala.concurrent.duration.FiniteDuration

/** One scripted step: a value or error, with an optional delay before it runs. */
final case class Script[+E, +T](
  response: Either[E, T],
  delay: Option[FiniteDuration] = None,
)

object Script:
  def ok[E, T](value: T): Script[E, T] = Script(Right(value))
  def fail[E, T](error: E): Script[E, T] = Script(Left(error))
  def after[E, T](d: FiniteDuration, value: T): Script[E, T] = Script(Right(value), Some(d))
  def failAfter[E, T](d: FiniteDuration, error: E): Script[E, T] = Script(Left(error), Some(d))
