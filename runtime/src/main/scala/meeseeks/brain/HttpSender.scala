package meeseeks.brain

import kyo.*
import meeseeks.port.BrainError

/** Minimal HTTP POST abstraction.
  *
  * Returns the raw (statusCode, body) pair so callers can interpret the response. Injected as a
  * constructor dependency so tests can substitute a scripted fake without a real network.
  */
trait HttpSender:
  def post(
      url: String,
      headers: Map[String, String],
      body: String
  ): (Int, String) < (Sync & Abort[BrainError])
