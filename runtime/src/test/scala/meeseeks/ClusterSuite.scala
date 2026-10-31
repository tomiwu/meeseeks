package meeseeks

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.actor.typed.receptionist.*
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.*

/** Proves: two Pekko Cluster nodes start on one machine, join, and exchange one message. */
class ClusterSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 60.seconds

  private def clusterConfig(port: Int): com.typesafe.config.Config =
    ConfigFactory
      .parseString(s"""
        pekko {
          actor.provider = cluster
          remote.artery {
            transport          = tcp
            canonical.hostname = "127.0.0.1"
            canonical.port     = $port
          }
          cluster {
            seed-nodes = ["pekko://MeeseeksCluster@127.0.0.1:${ClusterSuite.seedPort}"]
            downing-provider-class =
              "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
            split-brain-resolver.active-strategy = keep-oldest
          }
          loglevel                         = "WARNING"
          log-dead-letters                 = off
          log-dead-letters-during-shutdown = off
        }
      """)
      .withFallback(ConfigFactory.load())

  test("two nodes join cluster and exchange message") {
    import ClusterSuite.*

    val received = Promise[String]()

    val sys1 = ActorSystem[Nothing](echoGuardian, "MeeseeksCluster", clusterConfig(seedPort))
    val sys2 = ActorSystem[Nothing](
      clientGuardian(received),
      "MeeseeksCluster",
      clusterConfig(clientPort),
    )

    try
      val reply = Await.result(received.future, 30.seconds)
      assertEquals(reply, "pong:hello")
    finally
      sys2.terminate()
      val _ = Await.ready(sys2.whenTerminated, 10.seconds)
      sys1.terminate()
      val _ = Await.ready(sys1.whenTerminated, 10.seconds)
      ()
  }

object ClusterSuite:

  val seedPort: Int = 25520
  val clientPort: Int = 25521

  val echoKey: ServiceKey[EchoMsg] = ServiceKey[EchoMsg]("echo")

  sealed trait EchoMsg
  final case class Ping(msg: String, replyTo: ActorRef[String])
      extends EchoMsg
      with CborSerializable

  val echoGuardian: Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>
      val echo = ctx.spawn(echoActor, "echo")
      ctx.system.receptionist ! Receptionist.Register(echoKey, echo)
      Behaviors.empty
    }

  val echoActor: Behavior[EchoMsg] =
    Behaviors.receiveMessage:
      case Ping(msg, replyTo) =>
        replyTo ! s"pong:$msg"
        Behaviors.same

  sealed trait FinderMsg
  private final case class Listing(l: Receptionist.Listing) extends FinderMsg
  private final case class EchoReply(s: String) extends FinderMsg

  def clientGuardian(promise: Promise[String]): Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>
      val _ = ctx.spawn(finderBehavior(promise), "finder")
      Behaviors.empty
    }

  def finderBehavior(promise: Promise[String]): Behavior[FinderMsg] =
    Behaviors.setup[FinderMsg] { ctx =>
      val listingRef = ctx.messageAdapter[Receptionist.Listing](Listing.apply)
      ctx.system.receptionist ! Receptionist.Subscribe(echoKey, listingRef)
      val replyRef = ctx.messageAdapter[String](EchoReply.apply)

      Behaviors.receiveMessage:
        case Listing(l) =>
          // Send at most one Ping; ignore listing updates after the promise is done.
          if !promise.isCompleted then
            l.serviceInstances(echoKey).headOption.foreach { echo =>
              echo ! Ping("hello", replyRef)
            }
          Behaviors.same
        case EchoReply(s) =>
          val _ = promise.trySuccess(s)
          Behaviors.stopped
    }
