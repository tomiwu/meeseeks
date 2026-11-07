package meeseeks.cluster

import java.util.UUID
import kyo.*
import meeseeks.port.WorkItem
import meeseeks.port.WorkResult
import meeseeks.port.Worker
import meeseeks.port.WorkerError
import meeseeks.protocol.TokenStore
import meeseeks.protocol.WorkReply
import meeseeks.protocol.WorkRequest
import meeseeks.protocol.readWorkResult
import meeseeks.protocol.verifyMessage
import meeseeks.protocol.workEnvelope
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

/** Live Worker: sends a work item to a worker node in the cluster.
  *
  * The server issues a short-lived token for each request. The worker node repeats the token in its
  * reply. The server refuses a reply with a wrong or expired token before it uses the result.
  * `find` gives the worker node, or None when no node is available.
  */
final class ClusterWorker(
    system: ActorSystem[?],
    tokens: TokenStore,
    find: () => Future[Option[ActorRef[WorkRequest]]],
    timeout: FiniteDuration = ClusterWorker.DefaultTimeout
) extends Worker:

  private given ExecutionContext = ExecutionContext.parasitic
  private given Timeout = Timeout(timeout)

  override def submit(item: WorkItem): WorkResult < (Sync & Abort[WorkerError]) =
    Sync
      .defer(await(item))
      .map:
        case Right(result) => result
        case Left(error)   => Abort.fail(error)

  private def await(item: WorkItem): Either[WorkerError, WorkResult] =
    val limit = scala.concurrent.duration.Duration(timeout.toMillis * 2 + 1000, "ms")
    try Await.result(attempt(item), limit)
    catch case _: TimeoutException => Left(WorkerError.Unreachable("worker did not answer"))

  private def attempt(item: WorkItem): Future[Either[WorkerError, WorkResult]] =
    find()
      .flatMap:
        case None         => Future.successful(Left(WorkerError.Unreachable("no worker available")))
        case Some(target) => request(target, item)
      .recover { case _ => Left(WorkerError.Unreachable("worker did not answer")) }

  private def request(
      target: ActorRef[WorkRequest],
      item: WorkItem
  ): Future[Either[WorkerError, WorkResult]] =
    val token = tokens.issue("server-" + system.address.toString, TokenStore.DefaultTtlMillis)
    val envelope = workEnvelope(item, UUID.randomUUID().toString, token.id)
    val reply: Future[WorkReply] =
      target.ask[WorkReply](replyTo => WorkRequest(envelope, replyTo))(using
        summon[Timeout],
        system.scheduler
      )
    reply.map(r => check(envelope.taskId, r).flatMap(read))

  private def check(taskId: String, reply: WorkReply): Either[WorkerError, WorkReply] =
    verifyMessage(reply.envelope, tokens) match
      case Left(_) => Left(WorkerError.Failed("reply token refused"))
      case Right(_) if reply.envelope.taskId != taskId =>
        Left(WorkerError.Failed("reply task mismatch"))
      case Right(_) => Right(reply)

  private def read(reply: WorkReply): Either[WorkerError, WorkResult] =
    readWorkResult(reply).left.map(WorkerError.Failed(_))

end ClusterWorker

object ClusterWorker:

  val DefaultTimeout: FiniteDuration = 5.minutes

  /** Build a ClusterWorker that finds a worker node through the receptionist. */
  def live(
      system: ActorSystem[?],
      tokens: TokenStore,
      timeout: FiniteDuration = DefaultTimeout
  ): ClusterWorker =
    val findTimeout = Timeout(10.seconds)
    def find(): Future[Option[ActorRef[WorkRequest]]] =
      given ExecutionContext = ExecutionContext.parasitic
      system.receptionist
        .ask[Receptionist.Listing](Receptionist.Find(WorkerNode.key, _))(using
          findTimeout,
          system.scheduler
        )
        .map(_.serviceInstances(WorkerNode.key).headOption)
    new ClusterWorker(system, tokens, () => find(), timeout)

end ClusterWorker
