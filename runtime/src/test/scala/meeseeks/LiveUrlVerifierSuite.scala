package meeseeks

import kyo.*
import meeseeks.port.LiveUrlVerifier
import meeseeks.port.VerifierError
import meeseeks.research.UrlStatus

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient

/** Unit tests for LiveUrlVerifier. Uses a local JDK HttpServer — no external network required. */
class LiveUrlVerifierSuite extends munit.FunSuite:

  import AllowUnsafe.embrace.danger

  private def run[E: ConcreteTag, T](effect: T < (Sync & Abort[E])): Result[E, T] =
    Sync.Unsafe.evalOrThrow(Abort.run[E](effect))

  /** Start a local HTTP server on an ephemeral port. The returned server must be stopped after use.
    */
  private def startServer(statusCode: Int): (HttpServer, Int) =
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    server.createContext(
      "/",
      { exchange =>
        exchange.sendResponseHeaders(statusCode, -1)
        exchange.getResponseBody.close()
      }
    )
    server.start()
    (server, server.getAddress.getPort)

  test("LiveUrlVerifier returns Supported for HTTP 200") {
    val (server, port) = startServer(200)
    try
      val verifier = new LiveUrlVerifier(HttpClient.newHttpClient(), 5)
      val result = run(verifier.verify(s"http://localhost:$port/ok"))
      result.fold(
        status => assertEquals(status, UrlStatus.Supported),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally server.stop(0)
  }

  test("LiveUrlVerifier returns Supported for HTTP 204") {
    val (server, port) = startServer(204)
    try
      val verifier = new LiveUrlVerifier(HttpClient.newHttpClient(), 5)
      val result = run(verifier.verify(s"http://localhost:$port/no-content"))
      result.fold(
        status => assertEquals(status, UrlStatus.Supported),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally server.stop(0)
  }

  test("LiveUrlVerifier returns NotSupported for HTTP 404") {
    val (server, port) = startServer(404)
    try
      val verifier = new LiveUrlVerifier(HttpClient.newHttpClient(), 5)
      val result = run(verifier.verify(s"http://localhost:$port/missing"))
      result.fold(
        status => assertEquals(status, UrlStatus.NotSupported),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally server.stop(0)
  }

  test("LiveUrlVerifier returns NotSupported for HTTP 403") {
    val (server, port) = startServer(403)
    try
      val verifier = new LiveUrlVerifier(HttpClient.newHttpClient(), 5)
      val result = run(verifier.verify(s"http://localhost:$port/forbidden"))
      result.fold(
        status => assertEquals(status, UrlStatus.NotSupported),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally server.stop(0)
  }

  test("LiveUrlVerifier returns Unreachable for HTTP 500") {
    val (server, port) = startServer(500)
    try
      val verifier = new LiveUrlVerifier(HttpClient.newHttpClient(), 5)
      val result = run(verifier.verify(s"http://localhost:$port/error"))
      result.fold(
        status => assertEquals(status, UrlStatus.Unreachable),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally server.stop(0)
  }

  test("LiveUrlVerifier returns Unreachable for a port with no listener") {
    // Pick a port that is very unlikely to be in use.
    val verifier = new LiveUrlVerifier(HttpClient.newHttpClient(), 2)
    val result = run(verifier.verify("http://localhost:19999/no-listener"))
    result.fold(
      status => assertEquals(status, UrlStatus.Unreachable),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveUrlVerifier returns Unreachable for a malformed URL") {
    val verifier = new LiveUrlVerifier(HttpClient.newHttpClient(), 2)
    val result = run(verifier.verify("not-a-valid-url"))
    result.fold(
      status => assertEquals(status, UrlStatus.Unreachable),
      e => fail(s"unexpected error: $e"),
      t => fail(s"unexpected panic: $t")
    )
  }

  test("LiveUrlVerifier.apply factory creates a working instance") {
    val (server, port) = startServer(200)
    try
      val verifier = LiveUrlVerifier(5)
      val result = run(verifier.verify(s"http://localhost:$port/ok"))
      result.fold(
        status => assertEquals(status, UrlStatus.Supported),
        e => fail(s"unexpected error: $e"),
        t => fail(s"unexpected panic: $t")
      )
    finally server.stop(0)
  }

end LiveUrlVerifierSuite
