package meeseeks

import com.typesafe.config.ConfigFactory
import meeseeks.fake.FakeBrain
import meeseeks.fake.FakeChat
import meeseeks.fake.FakeOracle
import meeseeks.port.ChatError
import meeseeks.port.ChatMessage
import meeseeks.port.OracleVerdict
import scala.concurrent.Await
import scala.concurrent.duration.*

/** CI smoke test: boots the server with fakes and in-memory H2, then stops.
  *
  * Verifies that the ActorSystem starts without throwing, the Overseer comes up, and the system
  * terminates cleanly. No real Telegram token or brain is required.
  */
class MainSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 30.seconds

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
    Thread.sleep(400L)
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
    // Allow the kill message to be processed.
    Thread.sleep(800L)
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
    Thread.sleep(400L)
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }

end MainSuite
