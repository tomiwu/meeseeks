package meeseeks.port

import kyo.*
import meeseeks.research.UrlStatus

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Live UrlVerifier: sends a HEAD request (without downloading the body) with a timeout.
  *
  * Maps 2xx responses to Supported, 4xx to NotSupported, and all other outcomes (5xx, connection
  * errors, timeouts, malformed URLs) to Unreachable.
  *
  * @param http
  *   HttpClient for requests; injectable for tests
  * @param timeoutSeconds
  *   request timeout in seconds
  */
final class LiveUrlVerifier(http: HttpClient, timeoutSeconds: Int = 10) extends UrlVerifier:

  override def verify(url: String): UrlStatus < (Sync & Abort[VerifierError]) =
    Sync.defer:
      statusToUrl(headStatus(url))

  private def headStatus(url: String): Int =
    try
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .timeout(Duration.ofSeconds(timeoutSeconds.toLong))
        .build()
      http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    catch case _: Exception => -1

  private def statusToUrl(code: Int): UrlStatus =
    if isSupported(code) then UrlStatus.Supported
    else statusAfterSuccess(code)

  private def statusAfterSuccess(code: Int): UrlStatus =
    if isNotSupported(code) then UrlStatus.NotSupported
    else UrlStatus.Unreachable

  private def isSupported(code: Int): Boolean =
    code >= 200 && code < 300

  private def isNotSupported(code: Int): Boolean =
    code >= 400 && code < 500

object LiveUrlVerifier:
  def apply(timeoutSeconds: Int = 10): LiveUrlVerifier =
    new LiveUrlVerifier(HttpClient.newHttpClient(), timeoutSeconds)
