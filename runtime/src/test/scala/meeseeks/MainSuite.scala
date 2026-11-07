package meeseeks

import com.typesafe.config.ConfigFactory
import meeseeks.fake.FakeBrain
import meeseeks.fake.FakeChat
import meeseeks.fake.FakeOracle
import meeseeks.port.ChatError
import meeseeks.port.ChatMessage
import meeseeks.port.OracleVerdict
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import scala.concurrent.Await
import scala.concurrent.duration.*

/** CI smoke test: boots the server with fakes and in-memory H2, then stops.
  *
  * Verifies that the ActorSystem starts without throwing, the Overseer comes up, and the system
  * terminates cleanly. No real Telegram token or brain is required.
  */
class MainSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 30.seconds

  private val kit = ActorTestKit()

  override def afterAll(): Unit = kit.shutdownTestKit()

  private def fakeChat(config: ServerConfig): FakeChat = config.chat match
    case f: FakeChat => f
    case other       => fail(s"expected FakeChat, got $other")

  /** Wait with a Pekko probe until the condition holds. Fail at the timeout. */
  private def awaitCondition(what: String)(cond: => Boolean): Unit =
    kit.createTestProbe[Any]().awaitAssert(assert(cond, what), 10.seconds)

  test("Server boots with fake components and in-memory H2 then stops cleanly") {
    val config = ServerConfig(
      writerId = "test-main-1",
      ownerId = "owner-99",
      chat = FakeChat.withMessages(),
      brain = FakeBrain.withValues("ok"),
      oracle = FakeOracle.withVerdicts(OracleVerdict.Pass),
      pekkoConfig = ConfigFactory.load()
    )

    val system = Server.boot(config)
    awaitCondition("chat poll started")(fakeChat(config).receiveCalls.get() > 0)
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }

  test("Server handles kill command from owner") {
    val ownerMsg = ChatMessage("owner-77", "kill")
    val config = ServerConfig(
      writerId = "test-main-2",
      ownerId = "owner-77",
      chat = FakeChat.withMessages(ownerMsg),
      brain = FakeBrain.withValues("ok"),
      oracle = FakeOracle.withVerdicts(OracleVerdict.Pass),
      pekkoConfig = ConfigFactory.load()
    )

    val system = Server.boot(config)

    awaitCondition("kill acknowledged")(
      fakeChat(config).sent.contains("Kill sent. No new permits.")
    )
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }

  test("Server ignores messages from non-owners") {
    val strangerMsg = ChatMessage("stranger-1", "kill")
    val config = ServerConfig(
      writerId = "test-main-3",
      ownerId = "owner-55",
      chat = FakeChat.withMessages(strangerMsg),
      brain = FakeBrain.withValues("ok"),
      oracle = FakeOracle.withVerdicts(OracleVerdict.Pass),
      pekkoConfig = ConfigFactory.load()
    )

    val system = Server.boot(config)
    awaitCondition("stranger message consumed")(fakeChat(config).receiveCalls.get() > 1)
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }

  test("Server handles resume command from owner") {
    val resumeMsg = ChatMessage("owner-77", "resume")
    val config = ServerConfig(
      writerId = "test-main-4",
      ownerId = "owner-77",
      chat = FakeChat.withMessages(resumeMsg),
      brain = FakeBrain.withValues("ok"),
      oracle = FakeOracle.withVerdicts(OracleVerdict.Pass),
      pekkoConfig = ConfigFactory.load()
    )

    val system = Server.boot(config)
    awaitCondition("resume acknowledged")(fakeChat(config).sent.contains("Breaker resumed."))
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }

  test("Server accepts valid build task from owner and spawns Meeseeks") {
    val taskMsg = ChatMessage("owner-77", "build: test goal\ncheck: sbt test")
    val config = ServerConfig(
      writerId = "test-main-5",
      ownerId = "owner-77",
      chat = FakeChat.withMessages(taskMsg),
      brain = FakeBrain.withValues("ok"),
      oracle = FakeOracle.withVerdicts(OracleVerdict.Pass),
      pekkoConfig = ConfigFactory.load()
    )

    val system = Server.boot(config)
    awaitCondition("task accepted")(fakeChat(config).sent.exists(_.endsWith("accepted.")))
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }

  test("Server rejects task with no check and sends refusal to owner") {
    val badMsg = ChatMessage("owner-77", "some text with no check field")
    val config = ServerConfig(
      writerId = "test-main-6",
      ownerId = "owner-77",
      chat = FakeChat.withMessages(badMsg),
      brain = FakeBrain.withValues("ok"),
      oracle = FakeOracle.withVerdicts(OracleVerdict.Pass),
      pekkoConfig = ConfigFactory.load()
    )

    val system = Server.boot(config)
    awaitCondition("refusal sent")(fakeChat(config).sent.contains(Gateway.refusalText))
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }

end MainSuite
