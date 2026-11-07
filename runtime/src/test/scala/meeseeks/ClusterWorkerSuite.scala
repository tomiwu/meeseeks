package meeseeks

import kyo.{Abort, AllowUnsafe, Sync}
import meeseeks.cluster.ClusterWorker
import meeseeks.cluster.WorkerConfig
import meeseeks.cluster.WorkerNode
import meeseeks.fake.FakeSandbox
import meeseeks.fake.FakeClock
import meeseeks.port.*
import meeseeks.protocol.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*

/** Protocol-level tests for the live Worker. Fakes only: no Podman, no network. */
class ClusterWorkerSuite extends munit.FunSuite:

  private val testKit = ActorTestKit("ClusterWorkerSuite")
  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val item = WorkItem("goal", "echo hi")

  private def run(w: Worker): Either[WorkerError, WorkResult] =
    import AllowUnsafe.embrace.danger
    Sync.Unsafe.evalOrThrow(Abort.run[WorkerError](w.submit(item))).toEither.left.map {
      case e: WorkerError => e
      case other          => WorkerError.Failed(other.toString)
    }

  private def workerFor(
      target: Option[ActorRef[WorkRequest]],
      tokens: TokenStore = TokenStore.inMemory(),
      timeout: FiniteDuration = 5.seconds
  ) = new ClusterWorker(testKit.system, tokens, () => Future.successful(target), timeout)

  private def node(sandbox: Sandbox, name: String) =
    testKit.spawn(WorkerNode(sandbox), name)

  test("a work item runs in the sandbox and returns WorkResult") {
    val ref = node(FakeSandbox.withResults(SandboxResult(0, "hi", "")), "n1")
    assertEquals(run(workerFor(Some(ref))), Right(WorkResult(0, "hi")))
  }

  test("a non-zero exit code is a result, not an error") {
    val ref = node(FakeSandbox.withResults(SandboxResult(3, "bad", "e")), "n2")
    assertEquals(run(workerFor(Some(ref))), Right(WorkResult(3, "bad")))
  }

  test("a sandbox error becomes WorkerError.Failed") {
    val ref = node(FakeSandbox.withError(SandboxError.Unavailable("no podman")), "n3")
    assert(run(workerFor(Some(ref))).left.exists(_.isInstanceOf[WorkerError.Failed]))
  }

  test("every sandbox error kind becomes WorkerError.Failed") {
    val errors = List(SandboxError.ContainerFailed("c"), SandboxError.Timeout("t"))
    errors.zipWithIndex.foreach { (error, i) =>
      val ref = node(FakeSandbox.withError(error), s"err-$i")
      assert(run(workerFor(Some(ref))).left.exists(_.isInstanceOf[WorkerError.Failed]))
    }
  }

  test("no worker node gives Unreachable") {
    assert(run(workerFor(None)).left.exists(_.isInstanceOf[WorkerError.Unreachable]))
  }

  test("a silent worker node gives Unreachable after the timeout") {
    val probe = testKit.createTestProbe[WorkRequest]()
    val result = run(workerFor(Some(probe.ref), timeout = 300.millis))
    assert(result.left.exists(_.isInstanceOf[WorkerError.Unreachable]))
  }

  test("a reply with an expired token is refused") {
    val clock = FakeClock(1_000L)
    val tokens = TokenStore.inMemory(clock)
    val probe = testKit.createTestProbe[WorkRequest]()
    val done = Promise[Either[WorkerError, WorkResult]]()
    val worker = workerFor(Some(probe.ref), tokens, 5.seconds)
    val _ = Future(done.success(run(worker)))(using scala.concurrent.ExecutionContext.global)
    val req = probe.expectMessageType[WorkRequest]
    clock.advance(TokenStore.DefaultTtlMillis + 1)
    req.replyTo ! resultReply(req.envelope, WorkResult(0, "late"))
    val result = scala.concurrent.Await.result(done.future, 5.seconds)
    assert(result.left.exists(_.isInstanceOf[WorkerError.Failed]))
  }

  test("a reply with an unknown token is refused") {
    val probe = testKit.createTestProbe[WorkRequest]()
    val done = Promise[Either[WorkerError, WorkResult]]()
    val worker = workerFor(Some(probe.ref))
    val _ = Future(done.success(run(worker)))(using scala.concurrent.ExecutionContext.global)
    val req = probe.expectMessageType[WorkRequest]
    val forged = req.envelope.copy(tokenId = "forged")
    req.replyTo ! resultReply(forged, WorkResult(0, "x"))
    val result = scala.concurrent.Await.result(done.future, 5.seconds)
    assert(result.left.exists(_.isInstanceOf[WorkerError.Failed]))
  }

  test("envelope round trip keeps goal and command") {
    val env = workEnvelope(item, "t1", "tok")
    assertEquals(readWorkItem(env), Right(item))
    assertEquals(readWorkItem(env.copy(message = A2AMessage("user", Nil))).isLeft, true)
  }

  test("a failed reply with no text and a reply with no exit code are errors") {
    val env = workEnvelope(item, "t1", "tok")
    assertEquals(readWorkResult(failureReply(env, "why")), Left("why"))
    assertEquals(
      readWorkResult(WorkReply(env, TaskState.Completed.toString)),
      Left("missing exit code")
    )
  }

  test("a request without a command gets a Failed reply") {
    val ref = node(FakeSandbox.withResults(), "n4")
    val probe = testKit.createTestProbe[WorkReply]()
    val bad = A2AEnvelope("t", "s", A2AMessage("user", Nil), "tok")
    ref ! WorkRequest(bad, probe.ref)
    assertEquals(probe.expectMessageType[WorkReply].state, TaskState.Failed.toString)
  }

  test("WorkerConfig reads env and hides the password") {
    val env = Map(
      "MEESEEKS_WORKER_HOST" -> "10.0.0.2",
      "MEESEEKS_WORKER_PORT" -> "2552",
      "MEESEEKS_SERVER_HOST" -> "10.0.0.1",
      "MEESEEKS_SERVER_PORT" -> "2551",
      "MEESEEKS_TLS_KEYSTORE" -> "/k.p12",
      "MEESEEKS_TLS_TRUSTSTORE" -> "/t.p12",
      "MEESEEKS_TLS_PASSWORD" -> "s3cret-pw"
    )
    val config = WorkerConfig.fromEnv(env).toOption.get
    assertEquals(config.lease, 60.seconds)
    assert(!config.toString.contains("s3cret-pw"))
    assertEquals(config.serverAddress.port, Some(2551))
    assert(WorkerConfig.fromEnv(env - "MEESEEKS_TLS_PASSWORD").isLeft)
    assert(WorkerConfig.fromEnv(env.updated("MEESEEKS_WORKER_PORT", "x")).isLeft)
    assert(WorkerConfig.fromEnv(env.updated("MEESEEKS_LEASE_SECONDS", "x")).isLeft)
    assertEquals(
      WorkerConfig.fromEnv(env.updated("MEESEEKS_LEASE_SECONDS", "7")).toOption.map(_.lease),
      Some(7.seconds)
    )
  }
