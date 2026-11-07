package meeseeks.cluster

import java.util.concurrent.Executors
import kyo.*
import meeseeks.KyoStep
import meeseeks.port.Sandbox
import meeseeks.port.SandboxError
import meeseeks.port.WorkResult
import meeseeks.protocol.WorkReply
import meeseeks.protocol.WorkRequest
import meeseeks.protocol.failureReply
import meeseeks.protocol.readWorkItem
import meeseeks.protocol.resultReply
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.ExecutionContext

/** Worker side of the work protocol.
  *
  * Registers with the receptionist so the server finds it. For each request it runs the command in
  * the local sandbox and sends one reply. The reply repeats the token ID of the request, and the
  * server checks that token. The node never logs a command or an output.
  */
object WorkerNode:

  val key: ServiceKey[WorkRequest] = ServiceKey[WorkRequest]("meeseeks-worker")

  def apply(sandbox: Sandbox): Behavior[WorkRequest] =
    Behaviors.setup { ctx =>
      ctx.system.receptionist ! Receptionist.Register(key, ctx.self)
      // Sandbox calls block on a process, so they run on virtual threads.
      given ExecutionContext =
        ExecutionContext.fromExecutor(Executors.newVirtualThreadPerTaskExecutor())
      Behaviors.receiveMessage { request =>
        handle(sandbox, request)
        Behaviors.same
      }
    }

  private def handle(sandbox: Sandbox, request: WorkRequest)(using ExecutionContext): Unit =
    readWorkItem(request.envelope) match
      case Left(reason) => request.replyTo ! failureReply(request.envelope, reason)
      case Right(item) =>
        val effect = Abort.run[SandboxError](sandbox.run(item.command))
        KyoStep
          .runToFuture(effect)
          .map(result => request.replyTo ! toReply(request, result))
          .recover { case _ => request.replyTo ! failureReply(request.envelope, "sandbox crashed") }
        ()

  private def toReply(
      request: WorkRequest,
      result: Result[SandboxError, meeseeks.port.SandboxResult]
  ): WorkReply =
    result.fold(
      ok => resultReply(request.envelope, WorkResult(ok.exitCode, ok.stdout)),
      err => failureReply(request.envelope, s"sandbox error: ${errorText(err)}"),
      _ => failureReply(request.envelope, "sandbox crashed")
    )

  private def errorText(error: SandboxError): String = error match
    case SandboxError.ContainerFailed(m) => m
    case SandboxError.Timeout(m)         => m
    case SandboxError.Unavailable(m)     => m

end WorkerNode
