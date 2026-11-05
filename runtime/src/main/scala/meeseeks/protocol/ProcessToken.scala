package meeseeks.protocol

import java.time.Instant
import java.util.UUID
import meeseeks.Clock
import meeseeks.CborSerializable

/** A short-lived per-process token. The server issues one token per process at start.
  *
  * A process presents its token ID with every protocol message. The server checks that the token
  * exists and has not expired before it writes any event to the journal (FR19).
  */
final case class ProcessToken(
    id: String,
    processId: String,
    issuedAt: Instant,
    ttlMillis: Long
) extends CborSerializable:
  def isExpired(now: Instant): Boolean =
    now.toEpochMilli - issuedAt.toEpochMilli > ttlMillis

/** Reason a token check failed. */
enum TokenError:
  case NotFound
  case Expired

/** Issues and verifies per-process tokens. Only the server holds a TokenStore instance. */
trait TokenStore:
  def issue(processId: String, ttlMillis: Long): ProcessToken
  def verify(tokenId: String): Either[TokenError, ProcessToken]

object TokenStore:

  /** Default token lifetime: 5 minutes. */
  val DefaultTtlMillis: Long = 300_000L

  /** An in-memory token store. Used by the server and in tests. */
  def inMemory(clock: Clock = Clock.system): TokenStore =
    new TokenStore:
      private var tokens = Map.empty[String, ProcessToken]

      def issue(processId: String, ttlMillis: Long): ProcessToken =
        val token = ProcessToken(
          UUID.randomUUID().toString,
          processId,
          Instant.ofEpochMilli(clock.currentTimeMillis()),
          ttlMillis
        )
        tokens = tokens + (token.id -> token)
        token

      def verify(tokenId: String): Either[TokenError, ProcessToken] =
        val now = Instant.ofEpochMilli(clock.currentTimeMillis())
        tokens.get(tokenId) match
          case None => Left(TokenError.NotFound)
          case Some(token) =>
            if token.isExpired(now) then Left(TokenError.Expired) else Right(token)

end TokenStore
