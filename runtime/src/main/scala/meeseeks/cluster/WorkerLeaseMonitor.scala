package meeseeks.cluster

import java.time.Instant
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
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

  private final class LeaseClock:
    var unreachableSince: Option[Instant] = None

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
          val clock = new LeaseClock
          timers.startTimerAtFixedRate(Tick, tickInterval)
          Behaviors.receiveMessage(onMessage(clock, ctx, serverAddress, leaseDuration))
        }
      }
      .narrow[Nothing]

  private def onMessage(
      clock: LeaseClock,
      ctx: ActorContext[Msg],
      serverAddress: Address,
      leaseDuration: FiniteDuration
  )(msg: Msg): Behavior[Msg] =
    msg match
      case WrappedMember(event) => onMember(clock, serverAddress, event)
      case other                => onNotMember(clock, ctx, serverAddress, leaseDuration, other)

  private def onNotMember(
      clock: LeaseClock,
      ctx: ActorContext[Msg],
      serverAddress: Address,
      leaseDuration: FiniteDuration,
      msg: Msg
  ): Behavior[Msg] =
    msg match
      case WrappedReach(event) => onReach(clock, serverAddress, event)
      case _                   => onTick(clock, ctx, serverAddress, leaseDuration)

  private def onMember(
      clock: LeaseClock,
      serverAddress: Address,
      event: ClusterEvent.MemberEvent
  ): Behavior[Msg] =
    event match
      case ClusterEvent.MemberUp(member) =>
        clearIfServer(clock, serverAddress, member.address)
      case other => onMemberRemoved(clock, serverAddress, other)

  private def onMemberRemoved(
      clock: LeaseClock,
      serverAddress: Address,
      event: ClusterEvent.MemberEvent
  ): Behavior[Msg] =
    event match
      case ClusterEvent.MemberRemoved(member, _) =>
        noteIfServer(clock, serverAddress, member.address)
      case _ => Behaviors.same

  private def onReach(
      clock: LeaseClock,
      serverAddress: Address,
      event: ClusterEvent.ReachabilityEvent
  ): Behavior[Msg] =
    event match
      case ClusterEvent.ReachableMember(member) =>
        clearIfServer(clock, serverAddress, member.address)
      case other => onUnreachable(clock, serverAddress, other)

  private def onUnreachable(
      clock: LeaseClock,
      serverAddress: Address,
      event: ClusterEvent.ReachabilityEvent
  ): Behavior[Msg] =
    event match
      case ClusterEvent.UnreachableMember(member) =>
        noteIfServer(clock, serverAddress, member.address)
      case _ => Behaviors.same

  private def clearIfServer(
      clock: LeaseClock,
      serverAddress: Address,
      address: Address
  ): Behavior[Msg] =
    if address == serverAddress then clock.unreachableSince = None
    Behaviors.same

  private def noteIfServer(
      clock: LeaseClock,
      serverAddress: Address,
      address: Address
  ): Behavior[Msg] =
    if address == serverAddress then noteAbsent(clock)
    Behaviors.same

  private def noteAbsent(clock: LeaseClock): Unit =
    if clock.unreachableSince.isEmpty then clock.unreachableSince = Some(Instant.now())

  private def onTick(
      clock: LeaseClock,
      ctx: ActorContext[Msg],
      serverAddress: Address,
      leaseDuration: FiniteDuration
  ): Behavior[Msg] =
    clock.unreachableSince.foreach { since =>
      stopIfDue(ctx, serverAddress, leaseDuration, since)
    }
    Behaviors.same

  private def stopIfDue(
      ctx: ActorContext[Msg],
      serverAddress: Address,
      leaseDuration: FiniteDuration,
      since: Instant
  ): Unit =
    val elapsed = java.time.Duration.between(since, Instant.now())
    if elapsed.toMillis >= leaseDuration.toMillis then terminate(ctx, serverAddress, elapsed)

  private def terminate(
      ctx: ActorContext[Msg],
      serverAddress: Address,
      elapsed: java.time.Duration
  ): Unit =
    ctx.log.warn(
      "Server {} absent for {}s; self-stopping worker.",
      serverAddress,
      elapsed.toSeconds
    )
    ctx.system.terminate()

end WorkerLeaseMonitor
