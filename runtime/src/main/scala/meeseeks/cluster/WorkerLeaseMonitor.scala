package meeseeks.cluster

import java.time.Instant
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.ClusterEvent
import org.apache.pekko.cluster.typed.Cluster
import org.apache.pekko.cluster.typed.Subscribe
import scala.concurrent.duration.*

/** Stops the actor system when the server node has been absent for leaseDuration.
  *
  * Subscribes to cluster member and reachability events. Starts counting when the server leaves or
  * becomes unreachable. Stops the actor system if the server does not return within leaseDuration.
  *
  * FR8: A worker with no server contact for 60 s stops itself.
  */
object WorkerLeaseMonitor:

  private sealed trait Msg
  private case object Tick extends Msg
  private final case class WrappedMember(e: ClusterEvent.MemberEvent) extends Msg
  private final case class WrappedReach(e: ClusterEvent.ReachabilityEvent) extends Msg

  def apply(
      serverAddress: Address,
      leaseDuration: FiniteDuration = 60.seconds,
      tickInterval: FiniteDuration = 5.seconds
  ): Behavior[Nothing] =
    Behaviors
      .setup[Msg] { ctx =>
        val cluster = Cluster(ctx.system)
        val memberAdapter =
          ctx.messageAdapter[ClusterEvent.MemberEvent](WrappedMember.apply)
        val reachAdapter =
          ctx.messageAdapter[ClusterEvent.ReachabilityEvent](WrappedReach.apply)

        cluster.subscriptions ! Subscribe(memberAdapter, classOf[ClusterEvent.MemberEvent])
        cluster.subscriptions ! Subscribe(reachAdapter, classOf[ClusterEvent.ReachabilityEvent])

        Behaviors.withTimers { timers =>
          var unreachableSince: Option[Instant] = None

          timers.startTimerAtFixedRate(Tick, tickInterval)

          Behaviors.receiveMessage {
            case WrappedMember(e) =>
              e match
                case ClusterEvent.MemberUp(m) if m.address == serverAddress =>
                  unreachableSince = None
                case ClusterEvent.MemberRemoved(m, _) if m.address == serverAddress =>
                  if unreachableSince.isEmpty then unreachableSince = Some(Instant.now())
                case _ => ()
              Behaviors.same

            case WrappedReach(e) =>
              e match
                case ClusterEvent.ReachableMember(m) if m.address == serverAddress =>
                  unreachableSince = None
                case ClusterEvent.UnreachableMember(m) if m.address == serverAddress =>
                  if unreachableSince.isEmpty then unreachableSince = Some(Instant.now())
                case _ => ()
              Behaviors.same

            case Tick =>
              unreachableSince.foreach { since =>
                val elapsed = java.time.Duration.between(since, Instant.now())
                if elapsed.toMillis >= leaseDuration.toMillis then
                  ctx.log.warn(
                    "Server {} absent for {}s; self-stopping worker.",
                    serverAddress,
                    elapsed.toSeconds
                  )
                  ctx.system.terminate()
              }
              Behaviors.same
          }
        }
      }
      .narrow[Nothing]

end WorkerLeaseMonitor
