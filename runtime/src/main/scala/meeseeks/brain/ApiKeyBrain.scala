package meeseeks.brain

import com.fasterxml.jackson.databind.ObjectMapper
import kyo.*
import meeseeks.port.Brain
import meeseeks.port.BrainError
import meeseeks.sandbox.Credential

/** Brain that calls the Anthropic Messages API with a bearer API key.
  *
  * The key is extracted from the Credential at call time and sent in the x-api-key header. It is
  * never logged: Credential.toString shows only the name.
  *
  * HTTP 429 responses are mapped to BrainError.RateLimit. A 429 body with error type
  * "usage_limit_error" or a message that mentions "usage" or "credit" is mapped to
  * BrainError.UsageLimit instead, so the Overseer circuit breaker can trip.
  *
  * @param credential
  *   credential whose value is the Anthropic API key; name is the env-var label (never logged)
  * @param sender
  *   injectable HTTP layer; production uses LiveHttpSender, CI uses FakeHttpSender
  * @param model
  *   Anthropic model ID (default: claude-opus-4-5)
  * @param baseUrl
  *   API base URL (default: https://api.anthropic.com)
  */
final class ApiKeyBrain(
    credential: Credential,
    sender: HttpSender,
    model: String = ApiKeyBrain.defaultModel,
    baseUrl: String = ApiKeyBrain.defaultBaseUrl
) extends Brain:

  private val mapper = new ObjectMapper()

  override def ask(prompt: String): String < (Sync & Abort[BrainError]) =
    val body = buildBody(prompt)
    val headers = Map(
      "content-type" -> "application/json",
      "x-api-key" -> credential.toEnvLine.split("=", 2)(1),
      "anthropic-version" -> "2023-06-01"
    )
    sender.post(s"$baseUrl/v1/messages", headers, body).flatMap { (status, responseBody) =>
      parseResponse(status, responseBody)
    }

  private def buildBody(prompt: String): String =
    val node = mapper.createObjectNode()
    val _ = node.put("model", model)
    val _ = node.put("max_tokens", 4096)
    val messages = node.putArray("messages")
    val msg = messages.addObject()
    val _ = msg.put("role", "user")
    val _ = msg.put("content", prompt)
    mapper.writeValueAsString(node)

  private def parseResponse(status: Int, body: String): String < Abort[BrainError] =
    if status == 429 then
      val (errorType, message) = extractError(body)
      if errorType.contains("usage") || message.contains("usage") ||
        message.contains("credit") || message.contains("quota")
      then Abort.fail(BrainError.UsageLimit(s"Anthropic usage limit: $message"))
      else Abort.fail(BrainError.RateLimit(s"Anthropic rate limit: $message"))
    else if status != 200 then
      Abort.fail(BrainError.Unavailable(s"Anthropic API returned status $status"))
    else
      val rootOrError: Either[BrainError, com.fasterxml.jackson.databind.JsonNode] =
        try Right(mapper.readTree(body))
        catch
          case e: Exception =>
            Left(BrainError.Unavailable(s"Anthropic: invalid JSON response: ${e.getMessage}"))
      rootOrError match
        case Left(err) => Abort.fail(err)
        case Right(root) =>
          val content = root.path("content")
          if content.isMissingNode || content.size() == 0 then
            Abort.fail(BrainError.Unavailable("Anthropic response has no content"))
          else
            val text = (0 until content.size())
              .map(i => content.get(i))
              .filter(node => node.path("type").asText("") == "text")
              .map(_.path("text").asText(""))
              .mkString("")
            if text.isEmpty then
              Abort.fail(BrainError.Unavailable("Anthropic response has empty text"))
            else text

  private def extractError(body: String): (String, String) =
    try
      val root = mapper.readTree(body)
      val errorType = root.path("error").path("type").asText("")
      val message = root.path("error").path("message").asText("")
      (errorType, message)
    catch case _: Exception => ("", body)

object ApiKeyBrain:
  val defaultBaseUrl: String = "https://api.anthropic.com"
  val defaultModel: String = "claude-opus-4-5"
