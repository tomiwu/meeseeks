package meeseeks.brain

import kyo.*
import meeseeks.port.BrainError

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** Java HttpClient implementation of HttpSender.
  *
  * Not used in CI; CI uses FakeHttpSender. The HttpClient is injected so tests can substitute a
  * custom one if needed.
  */
final class LiveHttpSender(http: HttpClient) extends HttpSender:

  override def post(
      url: String,
      headers: Map[String, String],
      body: String
  ): (Int, String) < (Sync & Abort[BrainError]) =
    Sync.defer:
      val request = buildRequest(url, headers, body)
      val responseOrError: Either[BrainError, HttpResponse[String]] =
        try Right(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
        catch
          case e: Exception =>
            Left(BrainError.Unavailable(s"HTTP call failed: ${e.getMessage}"))
      responseOrError match
        case Left(err)       => Abort.fail(err)
        case Right(response) => (response.statusCode(), response.body())

  private def buildRequest(url: String, headers: Map[String, String], body: String): HttpRequest =
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    headers.foreach { (k, v) =>
      val _ = builder.header(k, v)
    }
    builder.build()

object LiveHttpSender:
  def apply(): LiveHttpSender = new LiveHttpSender(HttpClient.newHttpClient())
