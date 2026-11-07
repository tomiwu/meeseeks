package meeseeks

import com.typesafe.config.ConfigFactory
import meeseeks.cluster.WorkerLeaseMonitor
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.typed.Cluster
import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration.*

/** Proves: mTLS join with valid cert, rejection of invalid cert, and AT2 lease self-stop.
  *
  * AC1: A worker without a valid certificate cannot join. AC2: Work runs on a second node. AT2:
  * Worker with no server contact for leaseDuration stops itself.
  */
class MtlsClusterSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 180.seconds

  private lazy val pki: TestPki.PkiBundle = TestPki.setup()

  private val sysName = "MeeseeksCluster"

  /** Allocate a free TCP port by opening and immediately closing a socket.
    *
    * Each test call gets a unique port so parallel or repeated runs do not clash.
    */
  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  /** Wait with a Pekko probe until port appears as Up in the cluster, or fail at the timeout. */
  private def awaitMember(
      system: ActorSystem[?],
      port: Int,
      timeout: FiniteDuration
  ): Unit =
    val cluster = Cluster(system)
    TestProbe[Any]()(using system).awaitAssert(
      assert(
        cluster.state.members.exists(m =>
          m.address.port.contains(port) && m.status == MemberStatus.Up
        ),
        s"member on port $port is not Up"
      ),
      timeout
    )

  private def tlsConfig(
      port: Int,
      seed: Int,
      ks: java.nio.file.Path,
      ts: java.nio.file.Path
  ): com.typesafe.config.Config =
    ConfigFactory
      .parseString(s"""
        pekko {
          actor {
            provider = cluster
            allow-java-serialization = off
            serializers {
              jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer"
            }
            serialization-bindings {
              "meeseeks.CborSerializable" = jackson-cbor
            }
          }
          remote.artery {
            transport = tls-tcp
            canonical.hostname = "127.0.0.1"
            canonical.port = $port
            ssl {
              ssl-engine-provider = org.apache.pekko.remote.artery.tcp.ConfigSSLEngineProvider
              config-ssl-engine {
                key-store            = "${ks.toString.replace("\\", "/")}"
                key-store-password   = "${TestPki.password}"
                key-password         = "${TestPki.password}"
                trust-store          = "${ts.toString.replace("\\", "/")}"
                trust-store-password = "${TestPki.password}"
                protocol             = "TLSv1.3"
                require-mutual-authentication = on
                hostname-verification = off
              }
            }
          }
          cluster {
            seed-nodes = ["pekko://$sysName@127.0.0.1:$seed"]
            downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
            split-brain-resolver.active-strategy = keep-oldest
          }
          loglevel = "WARNING"
          log-dead-letters = off
          log-dead-letters-during-shutdown = off
        }
      """)
      .withFallback(ConfigFactory.load())

  test("AC1-AC2: valid-certificate worker joins cluster and runs work") {
    val sp = freePort()
    val wp = freePort()
    val received = Promise[String]()

    val serverSys = ActorSystem[Nothing](
      ClusterSuite.echoGuardian,
      sysName,
      tlsConfig(sp, sp, pki.server.keystorePath, pki.server.truststorePath)
    )
    val workerSys = ActorSystem[Nothing](
      ClusterSuite.clientGuardian(received),
      sysName,
      tlsConfig(wp, sp, pki.validWorker.keystorePath, pki.validWorker.truststorePath)
    )

    try
      // Wait for the worker to see the server as Up before checking the echo reply.
      // This decouples cluster-formation time from the echo exchange timeout.
      awaitMember(workerSys, sp, 60.seconds)
      val reply = Await.result(received.future, 30.seconds)
      assertEquals(reply, "pong:hello")
    finally
      workerSys.terminate()
      val _ = Await.ready(workerSys.whenTerminated, 60.seconds)
      serverSys.terminate()
      val _ = Await.ready(serverSys.whenTerminated, 60.seconds)
      ()
  }

  test("AC1: worker with invalid certificate cannot join") {
    val sp = freePort()
    val rp = freePort()

    val serverSys = ActorSystem[Nothing](
      Behaviors.empty[Nothing],
      sysName,
      tlsConfig(sp, sp, pki.server.keystorePath, pki.server.truststorePath)
    )
    val rogueSys = ActorSystem[Nothing](
      Behaviors.empty[Nothing],
      sysName,
      tlsConfig(rp, sp, pki.rogueWorker.keystorePath, pki.rogueWorker.truststorePath)
    )

    try
      // Allow time for a rogue connection attempt to fail.
      TestProbe[Any]()(using serverSys).expectNoMessage(5.seconds)
      val serverCluster = Cluster(serverSys)
      val members = serverCluster.state.members
      // The rogue worker must not appear as an Up member on the server.
      assert(
        !members.exists { m =>
          m.address.port.contains(rp) &&
          m.status == MemberStatus.Up
        },
        s"Rogue worker joined but should have been rejected. Members: $members"
      )
    finally
      rogueSys.terminate()
      val _ = Await.ready(rogueSys.whenTerminated, 10.seconds)
      serverSys.terminate()
      val _ = Await.ready(serverSys.whenTerminated, 10.seconds)
      ()
  }

  test("AT2: worker stops itself after leaseDuration without server contact") {
    val leaseDuration = 3.seconds
    val tickInterval = 1.second
    val sp = freePort()
    val wp = freePort()

    val serverSys = ActorSystem[Nothing](
      Behaviors.empty[Nothing],
      sysName,
      tlsConfig(sp, sp, pki.server.keystorePath, pki.server.truststorePath)
    )

    val serverAddress = Address("pekko", sysName, "127.0.0.1", sp)

    val workerGuardian: Behavior[Nothing] =
      Behaviors.setup[Nothing] { ctx =>
        val _ = ctx.spawn(
          WorkerLeaseMonitor(serverAddress, leaseDuration, tickInterval),
          "lease-monitor"
        )
        Behaviors.empty[Nothing]
      }

    val workerSys = ActorSystem[Nothing](
      workerGuardian,
      sysName,
      tlsConfig(wp, sp, pki.validWorker.keystorePath, pki.validWorker.truststorePath)
    )

    try
      // Wait until the worker sees the server as Up before cutting contact.
      awaitMember(workerSys, sp, 30.seconds)

      // Verify membership with a clear condition (awaitMember already guarantees this
      // unless the timeout passed, so the assert catches that case explicitly).
      val workerCluster = Cluster(workerSys)
      assert(
        workerCluster.state.members.exists(_.address.port.contains(sp)),
        "Worker must see the server as a cluster member before the AT2 cut"
      )

      // Terminate the server: simulates losing contact.
      serverSys.terminate()
      val _ = Await.ready(serverSys.whenTerminated, 10.seconds)

      // Worker must self-stop within leaseDuration + tick + buffer.
      Await.ready(workerSys.whenTerminated, (leaseDuration + tickInterval + 5.seconds))
    finally
      if !workerSys.whenTerminated.isCompleted then
        workerSys.terminate()
        val _ = Await.ready(workerSys.whenTerminated, 10.seconds)
      ()
  }
