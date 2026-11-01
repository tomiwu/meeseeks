package meeseeks.port

import kyo.*

enum BrainError:
  case RateLimit(message: String)
  case Unavailable(message: String)

/** Port: ask a language-model brain for a response. */
trait Brain:
  def ask(prompt: String): String < (Sync & Abort[BrainError])
