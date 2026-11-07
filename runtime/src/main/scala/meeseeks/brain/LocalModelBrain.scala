package meeseeks.brain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kyo.*
import meeseeks.port.Brain
import meeseeks.port.BrainError

/** Brain that calls a local OpenAI-compatible chat completion endpoint.
  *
  * Designed for Ollama and similar local model servers. No secret is required by default; set
  * MEESEEKS_LOCAL_MODEL_HOST and MEESEEKS_LOCAL_MODEL_NAME to configure.
  *
  * HTTP 429 is mapped to BrainError.RateLimit. Any other non-200 is Unavailable.
  *
  * @param host
  *   base URL of the OpenAI-compatible server (e.g. http://localhost:11434)
  * @param model
  *   model name understood by the server (e.g. llama3.2)
  * @param sender
  *   injectable HTTP layer; production uses LiveHttpSender, CI uses FakeHttpSender
  */
final class LocalModelBrain(
    host: String,
    model: String,
    sender: HttpSender
) extends Brain:

  private val mapper = new ObjectMapper()

  override def ask(prompt: String): String < (Sync & Abort[BrainError]) =
    val body = buildBody(prompt)
    val headers = Map("content-type" -> "application/json")
    sender.post(s"$host/v1/chat/completions", headers, body).flatMap { (status, responseBody) =>
      parseResponse(status, responseBody)
    }

  private def buildBody(prompt: String): String =
    val node = mapper.createObjectNode()
    val _ = node.put("model", model)
    val messages = node.putArray("messages")
    val msg = messages.addObject()
    val _ = msg.put("role", "user")
    val _ = msg.put("content", prompt)
    mapper.writeValueAsString(node)

  private def parseResponse(status: Int, body: String): String < Abort[BrainError] =
    if status == 429 then
      Abort.fail(BrainError.RateLimit(s"local model rate limit (HTTP 429): $body"))
    else parseOk(status, body)

  private def parseOk(status: Int, body: String): String < Abort[BrainError] =
    if status != 200 then Abort.fail(BrainError.Unavailable(s"local model returned status $status"))
    else contentFrom(body)

  private def contentFrom(body: String): String < Abort[BrainError] =
    readRoot(body) match
      case Left(err)   => Abort.fail(err)
      case Right(root) => choiceText(root)

  private def readRoot(body: String): Either[BrainError, JsonNode] =
    try Right(mapper.readTree(body))
    catch
      case e: Exception =>
        Left(BrainError.Unavailable(s"local model: invalid JSON: ${e.getMessage}"))

  private def choiceText(root: JsonNode): String < Abort[BrainError] =
    val choices = root.path("choices")
    if missingChoices(choices) then
      Abort.fail(BrainError.Unavailable("local model response has no choices"))
    else messageText(choices)

  private def missingChoices(choices: JsonNode): Boolean =
    choices.isMissingNode || choices.size() == 0

  private def messageText(choices: JsonNode): String < Abort[BrainError] =
    val content = choices.get(0).path("message").path("content").asText("")
    if content.isEmpty then
      Abort.fail(BrainError.Unavailable("local model response has empty content"))
    else content

object LocalModelBrain:
  val defaultHost: String = "http://localhost:11434"
  val defaultModel: String = "llama3.2"
