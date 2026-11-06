package meeseeks

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import meeseeks.brain.ApiKeyBrain
import meeseeks.brain.BackendKind
import meeseeks.brain.BrainConfig
import meeseeks.brain.LiveBrainRegistry
import meeseeks.brain.LiveHttpSender
import meeseeks.brain.LocalModelBrain
import meeseeks.brain.SubscriptionBrain
import meeseeks.build.BuildCheckOracle
import meeseeks.build.CheckRunner
import meeseeks.build.PodmanCheckRunner
import meeseeks.build.TestFileGuard
import meeseeks.channel.LiveTelegramApi
import meeseeks.channel.TelegramChat
import meeseeks.port.*
import meeseeks.sandbox.Credential
import meeseeks.sandbox.PodmanSandbox
import meeseeks.sandbox.SandboxConfig
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.Await

/** Guardian command: spawn one Meeseeks actor inside the actor system. */
sealed trait ServerCmd
final case class SpawnMeeseeks(id: String, task: Task, parentId: Option[String]) extends ServerCmd

/** Boot configuration. All ports are built before passing here; no secrets stored. */
final case class ServerConfig(
    writerId: String,
    ownerId: String,
    chat: Chat,
    brain: Brain,
    oracle: Oracle,
    pekkoConfig: Config,
    diskMonitor: Option[DiskMonitor] = None,
    checkRunner: Option[CheckRunner] = None,
    urlVerifier: Option[UrlVerifier] = None,
    researchJudge: Option[ResearchJudge] = None
)

/** Minimal brain for MEESEEKS_BRAIN=fake smoke mode: returns a canned answer. */
object SmokeOnlyBrain extends Brain:
  def ask(prompt: String): String < (Sync & Abort[BrainError]) = "smoke result"

/** Minimal oracle for MEESEEKS_BRAIN=fake smoke mode: always passes. */
object SmokeOnlyOracle extends Oracle:
  def judge(input: OracleInput): OracleVerdict < (Sync & Abort[OracleError]) = OracleVerdict.Pass

/** Start and stop the server ActorSystem. */
object Server:

  /** Boot the server. Returns the ActorSystem without blocking. */
  def boot(config: ServerConfig): ActorSystem[ServerCmd] =
    ActorSystem[ServerCmd](guardian(config), "meeseeks", config.pekkoConfig)

  /** Boot and block until the system terminates. Installs a JVM shutdown hook. */
  def start(config: ServerConfig): Unit =
    val system = boot(config)
    val _ = sys.addShutdownHook { system.terminate() }
    val _ = Await.result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)

  private def guardian(config: ServerConfig): Behavior[ServerCmd] =
    Behaviors.setup { ctx =>
      val overseer =
        ctx.spawn(Overseer(config.writerId, diskMonitor = config.diskMonitor), "overseer")
      val resultSink = ctx.spawn(chatSink(config.chat), "result-sink")

      // Shared factory: children send SpawnMeeseeks back to the guardian.
      val spawnFactory: Meeseeks.ChildFactory =
        (cid, ctask, pid) => ctx.self ! SpawnMeeseeks(cid, ctask, Some(pid))

      def oracleFor(task: Task): Oracle = task.taskType match
        case TaskType.Build =>
          config.checkRunner.fold(config.oracle)(runner =>
            new BuildCheckOracle(new TestFileGuard(Set.empty), runner, task.check)
          )
        case TaskType.Research =>
          config.oracle

      def spawnOne(id: String, task: Task, parentId: Option[String]): Unit =
        val _ = ctx.spawn(
          Meeseeks(
            id,
            task,
            overseer,
            config.brain,
            oracleFor(task),
            resultSink,
            parentId = parentId,
            childFactory = Some(spawnFactory)
          ),
          id
        )

      val counter = new AtomicInteger(0)
      val guardian = ctx.self
      val _ = Thread
        .ofVirtual()
        .name("chat-poll")
        .start(() => pollLoop(config, ctx.system, guardian, overseer, counter))

      Behaviors.receiveMessage { case SpawnMeeseeks(id, task, parentId) =>
        spawnOne(id, task, parentId)
        Behaviors.same
      }
    }

  private def chatSink(chat: Chat): Behavior[String] =
    Behaviors.receive { (ctx, text) =>
      import AllowUnsafe.embrace.danger
      Sync.Unsafe
        .evalOrThrow(Abort.run[ChatError](chat.send(text)))
        .fold(
          _ => (),
          err => ctx.log.warn(s"chat send failed: $err"),
          t => ctx.log.error(s"chat send panic: ${t.getMessage}")
        )
      Behaviors.same
    }

  private def pollLoop(
      config: ServerConfig,
      system: ActorSystem[?],
      guardian: ActorRef[ServerCmd],
      overseer: ActorRef[Overseer.Command],
      counter: AtomicInteger
  ): Unit =
    var running = true
    while running && !system.whenTerminated.isCompleted do
      try
        import AllowUnsafe.embrace.danger
        Sync.Unsafe
          .evalOrThrow(Abort.run[ChatError](config.chat.receive()))
          .fold(
            msg =>
              if msg.sender == config.ownerId then
                handleOwner(msg.text, config.chat, guardian, overseer, counter)
              else (),
            {
              case ChatError.Disconnected(reason) =>
                System.err.println(s"[meeseeks] chat disconnected: $reason")
                Thread.sleep(5000L)
              case ChatError.Unauthorized(reason) =>
                System.err.println(s"[meeseeks] chat unauthorized: $reason")
                running = false
            },
            t =>
              System.err.println(s"[meeseeks] unexpected poll error: ${t.getMessage}")
              Thread.sleep(1000L)
          )
      catch
        case t: Throwable =>
          System.err.println(s"[meeseeks] poll exception: ${t.getMessage}")
          Thread.sleep(1000L)

  private def handleOwner(
      text: String,
      chat: Chat,
      guardian: ActorRef[ServerCmd],
      overseer: ActorRef[Overseer.Command],
      counter: AtomicInteger
  ): Unit =
    text.trim.toLowerCase match
      case "kill" =>
        overseer ! Overseer.Kill
        sendSafe(chat, "Kill sent. No new permits.")
      case "resume" =>
        overseer ! Overseer.ResumeBreaker
        sendSafe(chat, "Breaker resumed.")
      case _ =>
        Gateway.parse(text) match
          case Some(task) =>
            val id = s"m-${counter.incrementAndGet()}"
            guardian ! SpawnMeeseeks(id, task, None)
            sendSafe(chat, s"Task $id accepted.")
          case None =>
            sendSafe(chat, Gateway.refusalText)

  private def sendSafe(chat: Chat, text: String): Unit =
    import AllowUnsafe.embrace.danger
    try
      Sync.Unsafe
        .evalOrThrow(Abort.run[ChatError](chat.send(text)))
        .fold(
          _ => (),
          err => System.err.println(s"[meeseeks] send failed: $err"),
          t => System.err.println(s"[meeseeks] send panic: ${t.getMessage}")
        )
    catch case t: Throwable => System.err.println(s"[meeseeks] send error: ${t.getMessage}")

end Server

/** Production entry point. Run with: sbt "runtime/run" */
@main def run(): Unit =
  try startFromEnv(sys.env)
  catch
    case t: Throwable =>
      System.err.println(s"[meeseeks] fatal: ${t.getMessage}")
      sys.exit(2)

def startFromEnv(env: Map[String, String]): Unit =
  val tokenVal = requireEnv(env, "MEESEEKS_TELEGRAM_BOT_TOKEN")
  val ownerId = requireEnv(env, "MEESEEKS_OWNER_ID")
  val rawPath = env.getOrElse("MEESEEKS_JOURNAL_PATH", "./data/journal")
  val writerId = env.getOrElse("MEESEEKS_WRITER_ID", "prod-1")
  val brainMode = env.getOrElse("MEESEEKS_BRAIN", "subscription")

  val ownerIdLong = ownerId.toLongOption.getOrElse {
    System.err.println("[meeseeks] MEESEEKS_OWNER_ID must be a numeric chat ID")
    sys.exit(1)
  }

  val journalDir = ensureJournalDir(rawPath)
  val overrides = ConfigFactory.parseString(
    s"""meeseeks.writer-id = "$writerId"
       |meeseeks.journal.path = "${journalDir.getAbsolutePath}"
       |""".stripMargin
  )
  val pekkoConfig = overrides.withFallback(ConfigFactory.load())

  val telegramCred = Credential("MEESEEKS_TELEGRAM_BOT_TOKEN", tokenVal)
  val api = new LiveTelegramApi(telegramCred)
  val chat = new TelegramChat(api, ownerIdLong)

  val brain: Brain = selectBrain(env, brainMode)

  val diskMonitor = new LiveDiskMonitor(journalDir.toPath)
  val checkImage = env.getOrElse("MEESEEKS_SANDBOX_IMAGE", "meeseeks/brain:latest")
  val rawTaskDir = env.getOrElse("MEESEEKS_TASK_DIR", "./task")
  val taskDir = Paths.get(rawTaskDir).toAbsolutePath
  val sandboxConfig = SandboxConfig(image = checkImage, taskDir = taskDir)
  val checkRunner = new PodmanCheckRunner(new PodmanSandbox(sandboxConfig))
  val urlVerifier = LiveUrlVerifier()
  val researchJudge = new LiveResearchJudge(brain)

  Server.start(
    ServerConfig(
      writerId,
      ownerId,
      chat,
      brain,
      SmokeOnlyOracle,
      pekkoConfig,
      diskMonitor = Some(diskMonitor),
      checkRunner = Some(checkRunner),
      urlVerifier = Some(urlVerifier),
      researchJudge = Some(researchJudge)
    )
  )

private def ensureJournalDir(rawPath: String): File =
  val dir = new File(rawPath)
  if !dir.exists() then
    val _ = dir.mkdirs()
  dir

private val brainBuilders: Map[String, Map[String, String] => Brain] = Map(
  "fake" -> (_ => SmokeOnlyBrain),
  "subscription" -> buildSubscriptionBrain,
  "" -> buildSubscriptionBrain,
  "apikey" -> buildApiKeyBrain,
  "local" -> buildLocalModelBrain
)

private def selectBrain(env: Map[String, String], brainMode: String): Brain =
  brainBuilders.get(brainMode) match
    case Some(builder) => builder(env)
    case None =>
      System.err.println(
        s"[meeseeks] unknown MEESEEKS_BRAIN value: $brainMode; use fake/subscription/apikey/local"
      )
      sys.exit(1)

private def buildSubscriptionBrain(env: Map[String, String]): Brain =
  val image = env.getOrElse("MEESEEKS_SANDBOX_IMAGE", "meeseeks/brain:latest")
  val credOpt = env
    .get("MEESEEKS_BRAIN_SUBSCRIPTION_CREDENTIAL")
    .map(v => Credential("ANTHROPIC_API_KEY", v))
  SubscriptionBrain.live(image, credOpt)

private def buildApiKeyBrain(env: Map[String, String]): Brain =
  val key = env.getOrElse(
    "MEESEEKS_BRAIN_API_KEY", {
      System.err.println(
        "[meeseeks] MEESEEKS_BRAIN=apikey requires MEESEEKS_BRAIN_API_KEY to be set"
      )
      sys.exit(1)
    }
  )
  val model = env.getOrElse("MEESEEKS_BRAIN_API_MODEL", ApiKeyBrain.defaultModel)
  val sender = LiveHttpSender()
  new ApiKeyBrain(Credential("MEESEEKS_BRAIN_API_KEY", key), sender, model)

private def buildLocalModelBrain(env: Map[String, String]): Brain =
  val host = env.getOrElse("MEESEEKS_LOCAL_MODEL_HOST", LocalModelBrain.defaultHost)
  val model = env.getOrElse("MEESEEKS_LOCAL_MODEL_NAME", LocalModelBrain.defaultModel)
  val sender = LiveHttpSender()
  new LocalModelBrain(host, model, sender)

private def requireEnv(env: Map[String, String], name: String): String =
  env.getOrElse(
    name, {
      System.err.println(s"[meeseeks] required env var $name is not set")
      sys.exit(1)
    }
  )
