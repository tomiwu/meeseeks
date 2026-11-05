package meeseeks.port

import kyo.*
import meeseeks.research.UrlStatus

enum VerifierError:
  case Unavailable(message: String)

/** Port: fetch a URL and return whether the source supports the claim. */
trait UrlVerifier:
  def verify(url: String): UrlStatus < (Sync & Abort[VerifierError])
