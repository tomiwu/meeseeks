package meeseeks

import com.typesafe.config.ConfigFactory
import meeseeks.cluster.WorkerLeaseMonitor
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
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

  override val munitTimeout: Duration = 90.seconds

  private lazy val pki: TestPki.PkiBundle = TestPki.setup()

  private val sysName = "MeeseeksCluster"

  private val seedPort = 25530
  private val validWorkerPort = 25531
  private val rogueWorkerPort = 25532
  private val at2SeedPort = 25535
  private val at2WorkerPort = 25536

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
    val received = Promise[String]()

    val serverSys = ActorSystem[Nothing](
      ClusterSuite.echoGuardian,
      sysName,
      tlsConfig(seedPort, seedPort, pki.server.keystorePath, pki.server.truststorePath)
    )
    val workerSys = ActorSystem[Nothing](
      ClusterSuite.clientGuardian(received),
      sysName,
      tlsConfig(
        validWorkerPort,
        seedPort,
        pki.validWorker.keystorePath,
        pki.validWorker.truststorePath
      )
    )

    try
      val reply = Await.result(received.future, 30.seconds)
      assertEquals(reply, "pong:hello")
    finally
      workerSys.terminate()
      val _ = Await.ready(workerSys.whenTerminated, 10.seconds)
      serverSys.terminate()
      val _ = Await.ready(serverSys.whenTerminated, 10.seconds)
      ()
  }

  test("AC1: worker with invalid certificate cannot join") {
    val serverSeed = seedPort + 10

    val serverSys = ActorSystem[Nothing](
      Behaviors.empty[Nothing],
      sysName,
      tlsConfig(serverSeed, serverSeed, pki.server.keystorePath, pki.server.truststorePath)
    )
    val rogueSys = ActorSystem[Nothing](
      Behaviors.empty[Nothing],
      sysName,
      tlsConfig(
        rogueWorkerPort,
        serverSeed,
        pki.rogueWorker.keystorePath,
        pki.rogueWorker.truststorePath
      )
    )

    try
      // Allow time for a rogue connection attempt to fail.
      Thread.sleep(5000)
      val serverCluster = Cluster(serverSys)
      val members = serverCluster.state.members
      // The rogue worker must not appear as an Up member on the server.
      assert(
        !members.exists { m =>
          m.address.port.contains(rogueWorkerPort) &&
          m.status == org.apache.pekko.cluster.MemberStatus.Up
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

    val serverSys = ActorSystem[Nothing](
      Behaviors.empty[Nothing],
      sysName,
      tlsConfig(at2SeedPort, at2SeedPort, pki.server.keystorePath, pki.server.truststorePath)
    )

    val serverAddress = Address("pekko", sysName, "127.0.0.1", at2SeedPort)

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
      tlsConfig(
        at2WorkerPort,
        at2SeedPort,
        pki.validWorker.keystorePath,
        pki.validWorker.truststorePath
      )
    )

    try
      // Wait for both nodes to join.
      Thread.sleep(5000)

      // Verify the worker sees the server as Up before cutting contact.
      val workerCluster = Cluster(workerSys)
      assert(
        workerCluster.state.members.exists(_.address.port.contains(at2SeedPort)),
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
