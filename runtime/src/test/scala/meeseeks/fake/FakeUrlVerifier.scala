package meeseeks.fake

import kyo.*
import meeseeks.port.UrlVerifier
import meeseeks.port.VerifierError
import meeseeks.research.UrlStatus

/** In-memory UrlVerifier fake that returns scripted statuses from a URL map. */
final class FakeUrlVerifier(statuses: Map[String, UrlStatus]) extends UrlVerifier:
  override def verify(url: String): UrlStatus < (Sync & Abort[VerifierError]) =
    Sync.defer:
      statuses.getOrElse(url, UrlStatus.Unreachable)

object FakeUrlVerifier:
  def withStatuses(entries: (String, UrlStatus)*): FakeUrlVerifier =
    new FakeUrlVerifier(entries.toMap)
  def allSupported(urls: String*): FakeUrlVerifier =
    new FakeUrlVerifier(urls.map(_ -> UrlStatus.Supported).toMap)
