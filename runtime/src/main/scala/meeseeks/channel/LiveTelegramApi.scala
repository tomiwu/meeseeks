package meeseeks.channel

import com.fasterxml.jackson.databind.ObjectMapper
import kyo.*
import meeseeks.port.ChatError
import meeseeks.sandbox.Credential
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Telegram HTTP client backed by Java's built-in HttpClient.
  *
  * Not used in CI; CI uses FakeTelegramApi. The token is read from credential at runtime but is
  * never logged: Credential.toString masks the value.
  *
  * @param credential
  *   bot token credential; the value is used only in the URL path, never in log output
  */
final class LiveTelegramApi(credential: Credential) extends TelegramApi:

  private val token: String = credential.toEnvLine.split("=", 2)(1)
  private val baseUrl: String = s"https://api.telegram.org/bot$token"
  private val http: HttpClient = HttpClient.newHttpClient()
  private val mapper: ObjectMapper = new ObjectMapper()

  override def pollUpdates(offset: Long): List[TelegramUpdate] < (Sync & Abort[ChatError]) =
    Sync.defer {
      val url =
        s"$baseUrl/getUpdates?offset=$offset&timeout=25&allowed_updates=%5B%22message%22%5D"
      val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() != 200 then
        Abort.fail(ChatError.Disconnected(s"Telegram getUpdates returned ${resp.statusCode()}"))
      else
        val root = mapper.readTree(resp.body())
        if !root.path("ok").asBoolean(false) then
          Abort.fail(ChatError.Disconnected("Telegram response not ok"))
        else
          val results = root.path("result")
          (0 until results.size()).toList.flatMap { i =>
            val upd = results.get(i)
            val msg = upd.path("message")
            if msg.isMissingNode then None
            else
              val text = msg.path("text").asText("")
              if text.isEmpty then None
              else
                Some(
                  TelegramUpdate(
                    updateId = upd.path("update_id").asLong(),
                    fromId = msg.path("from").path("id").asLong().toString,
                    chatId = msg.path("chat").path("id").asLong(),
                    text = text
                  )
                )
          }
    }

  override def sendMessage(chatId: Long, text: String): Unit < (Sync & Abort[ChatError]) =
    Sync.defer {
      val node = mapper.createObjectNode()
      node.put("chat_id", chatId)
      node.put("text", text)
      val body = mapper.writeValueAsString(node)
      val req = HttpRequest
        .newBuilder(URI.create(s"$baseUrl/sendMessage"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() != 200 then
        Abort.fail(ChatError.Disconnected(s"Telegram sendMessage returned ${resp.statusCode()}"))
      else ()
    }
