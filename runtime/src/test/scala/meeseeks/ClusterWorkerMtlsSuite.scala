package meeseeks

import kyo.{Abort, AllowUnsafe, Sync}
import meeseeks.cluster.ClusterWorker
import meeseeks.cluster.WorkerNode
import meeseeks.cluster.clusterTlsConfig
import meeseeks.fake.FakeSandbox
import meeseeks.port.*
import meeseeks.protocol.TokenStore
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import scala.concurrent.Await
import scala.concurrent.duration.*

/** A second node with a valid certificate gets one work item and returns WorkResult (issue #67).
  *
  * Two ActorSystems in one JVM stand in for two JVMs. The sandbox is a fake: no Podman.
  */
class ClusterWorkerMtlsSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 180.seconds

  private lazy val pki: TestPki.PkiBundle = TestPki.setup()
  private val name = "meeseeks"

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  private def config(port: Int, seed: Int, certs: TestPki.NodeCerts) =
    clusterTlsConfig(
      "127.0.0.1",
      port,
      Address("pekko", name, "127.0.0.1", seed),
      certs.keystorePath,
      certs.truststorePath,
      TestPki.password
    )

  private def node(port: Int, seed: Int, certs: TestPki.NodeCerts, guardian: Behavior[Nothing]) =
    ActorSystem[Nothing](guardian, name, config(port, seed, certs))

  private def stop(system: ActorSystem[?]): Unit =
    system.terminate()
    val _ = Await.ready(system.whenTerminated, 30.seconds)

  private def submit(w: Worker, item: WorkItem) =
    import AllowUnsafe.embrace.danger
    Sync.Unsafe.evalOrThrow(Abort.run[WorkerError](w.submit(item))).toEither

  private def workerGuardian(sandbox: Sandbox): Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>
      val _ = ctx.spawn(WorkerNode(sandbox), "worker-node")
      Behaviors.empty
    }

  test("a valid-certificate worker node runs one work item and returns WorkResult") {
    val sp = freePort()
    val server = node(sp, sp, pki.server, Behaviors.empty[Nothing])
    val worker = node(
      freePort(),
      sp,
      pki.validWorker,
      workerGuardian(FakeSandbox.withResults(SandboxResult(0, "remote ok", "")))
    )
    try
      val live = ClusterWorker.live(server, TokenStore.inMemory(), 30.seconds)
      val probe = TestProbe[Any]()(using server)
      probe.awaitAssert(
        assert(submit(live, WorkItem("g", "echo")) == Right(WorkResult(0, "remote ok"))),
        90.seconds,
        1.second
      )
    finally
      stop(worker)
      stop(server)
  }
