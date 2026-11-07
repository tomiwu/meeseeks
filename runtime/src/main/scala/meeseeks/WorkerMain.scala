package meeseeks

import meeseeks.cluster.WorkerConfig
import meeseeks.cluster.WorkerLeaseMonitor
import meeseeks.cluster.WorkerNode
import meeseeks.cluster.workerPekkoConfig
import meeseeks.port.Sandbox
import meeseeks.sandbox.PodmanSandbox
import meeseeks.sandbox.SandboxConfig
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** Guardian of a worker node: the work handler and the lease monitor. */
def workerGuardian(config: WorkerConfig, sandbox: Sandbox): Behavior[Nothing] =
  Behaviors.setup[Nothing] { ctx =>
    val _ = ctx.spawn(WorkerNode(sandbox), "worker-node")
    val _ = ctx.spawn(WorkerLeaseMonitor(config.serverAddress, config.lease), "lease-monitor")
    Behaviors.empty
  }

/** Worker entry point. Run with: sbt "runtime/runMain meeseeks.runWorker"
  *
  * The node joins the cluster with its own certificate, runs Podman locally, and stops itself after
  * the lease without server contact.
  */
@main def runWorker(): Unit =
  WorkerConfig.fromEnv(sys.env).fold(exitWith, startWorker)

private def exitWith(reason: String): Unit =
  System.err.println(s"[meeseeks-worker] $reason")
  sys.exit(1)

def startWorker(config: WorkerConfig): Unit =
  val sandbox = new PodmanSandbox(SandboxConfig(image = config.image, taskDir = config.taskDir))
  val system =
    ActorSystem[Nothing](
      workerGuardian(config, sandbox),
      WorkerConfig.SystemName,
      workerPekkoConfig(config)
    )
  val _ = sys.addShutdownHook { system.terminate() }
  val _ = Await.result(system.whenTerminated, Duration.Inf)
