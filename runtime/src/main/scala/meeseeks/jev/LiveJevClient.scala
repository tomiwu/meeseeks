package meeseeks.jev

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kyo.*
import meeseeks.port.OracleError
import meeseeks.port.OracleInput
import meeseeks.port.OracleVerdict

/** Calls the Jev HTTP API to judge a Meeseeks step result.
  *
  * The API key is taken from JevConfig.credential and sent as a Bearer token. The secret is never
  * logged; Credential.toString shows only the name.
  *
  * Request: POST {baseUrl}/v1/judge with JSON body. Response: JSON with a verdict field.
  */
final class LiveJevClient(config: JevConfig, http: HttpClient, mapper: ObjectMapper)
    extends JevClient:

  override def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError]) =
    Sync.defer:
      val body = buildBody(input)
      val request = buildRequest(body)
      val responseOrError: Either[OracleError, HttpResponse[String]] =
        try Right(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
        catch
          case e: Exception =>
            Left(OracleError.Unavailable(s"Jev HTTP call failed: ${e.getMessage}"))
      responseOrError match
        case Left(err)       => Abort.fail(err)
        case Right(response) => parseResponse(response)

  private def buildBody(input: OracleInput): String =
    val node = mapper.createObjectNode()
    val _ = node.put("goal", input.goal)
    val _ = node.put("output", input.output)
    input.rubric.foreach(r => { val _ = node.put("rubric", r) })
    mapper.writeValueAsString(node)

  private def buildRequest(body: String): HttpRequest =
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl}/v1/judge"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    config.credential.foreach(c =>
      val _ = builder.header("Authorization", s"Bearer ${c.toEnvLine.split("=", 2).last}")
    )
    builder.build()

  private def parseResponse(
      response: HttpResponse[String]
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    if response.statusCode() == 401 then
      Abort.fail(OracleError.Unavailable("Jev: unauthorized — check the API key"))
    else parseOtherStatus(response)

  private def parseOtherStatus(
      response: HttpResponse[String]
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    if response.statusCode() != 200 then
      Abort.fail(OracleError.Unavailable(s"Jev: unexpected status ${response.statusCode()}"))
    else parseBody(response.body())

  private def parseBody(body: String): OracleVerdict < (Sync & Abort[OracleError]) =
    readVerdictTree(body) match
      case Left(err)   => Abort.fail(err)
      case Right(root) => verdictFrom(root)

  private def readVerdictTree(body: String): Either[OracleError, JsonNode] =
    try Right(mapper.readTree(body))
    catch
      case e: Exception =>
        Left(OracleError.Unavailable(s"Jev: invalid JSON response: ${e.getMessage}"))

  private def verdictFrom(root: JsonNode): OracleVerdict < (Sync & Abort[OracleError]) =
    val verdict = Option(root.get("verdict")).map(_.asText()).getOrElse("")
    val reason = Option(root.get("reason")).map(_.asText()).getOrElse("")
    val highConf = Option(root.get("highConfidence")).exists(_.asBoolean())
    knownVerdict(verdict, reason, highConf)

  private def knownVerdict(
      verdict: String,
      reason: String,
      highConf: Boolean
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    verdictTable.get(verdict) match
      case Some(make) => make(reason, highConf)
      case None       => Abort.fail(OracleError.InvalidInput(s"Jev: unknown verdict '$verdict'"))

  private def verdictTable
      : Map[String, (String, Boolean) => OracleVerdict < (Sync & Abort[OracleError])] =
    Map(
      "pass" -> passVerdict,
      "fail" -> failVerdict,
      "unsure" -> unsureVerdict,
      "stop" -> stopVerdict,
      "continue" -> continueVerdict
    )

  private def passVerdict(
      reason: String,
      highConf: Boolean
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    OracleVerdict.Pass

  private def failVerdict(
      reason: String,
      highConf: Boolean
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    OracleVerdict.Fail(reason)

  private def unsureVerdict(
      reason: String,
      highConf: Boolean
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    OracleVerdict.Unsure(reason)

  private def stopVerdict(
      reason: String,
      highConf: Boolean
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    OracleVerdict.Stop(reason, highConf)

  private def continueVerdict(
      reason: String,
      highConf: Boolean
  ): OracleVerdict < (Sync & Abort[OracleError]) =
    OracleVerdict.Continue

end LiveJevClient

object LiveJevClient:
  def apply(config: JevConfig): LiveJevClient =
    new LiveJevClient(config, HttpClient.newHttpClient(), new ObjectMapper())
