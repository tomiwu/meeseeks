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
      val statusCode =
        try
          val request = HttpRequest
            .newBuilder()
            .uri(URI.create(url))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong))
            .build()
          http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        catch case _: Exception => -1
      statusCode match
        case s if s >= 200 && s < 300 => UrlStatus.Supported
        case s if s >= 400 && s < 500 => UrlStatus.NotSupported
        case _                        => UrlStatus.Unreachable

object LiveUrlVerifier:
  def apply(timeoutSeconds: Int = 10): LiveUrlVerifier =
    new LiveUrlVerifier(HttpClient.newHttpClient(), timeoutSeconds)
