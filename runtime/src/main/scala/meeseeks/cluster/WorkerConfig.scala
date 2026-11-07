package meeseeks.cluster

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.nio.file.Path
import java.nio.file.Paths
import org.apache.pekko.actor.Address
import scala.concurrent.duration.*

/** Settings of one worker node. The TLS password is a secret, so toString hides it. */
final case class WorkerConfig(
    host: String,
    port: Int,
    serverHost: String,
    serverPort: Int,
    keystore: Path,
    truststore: Path,
    tlsPassword: String,
    image: String,
    taskDir: Path,
    lease: FiniteDuration
):
  override def toString: String =
    s"WorkerConfig($host:$port, server=$serverHost:$serverPort, tlsPassword=<hidden>)"

  def serverAddress: Address = Address("pekko", WorkerConfig.SystemName, serverHost, serverPort)

object WorkerConfig:

  /** The actor system name. The server and all workers must use the same name. */
  val SystemName: String = "meeseeks"

  /** Read the worker settings from the environment. The error text names the bad variable. */
  def fromEnv(env: Map[String, String]): Either[String, WorkerConfig] =
    for
      host <- required(env, "MEESEEKS_WORKER_HOST")
      port <- number(env, "MEESEEKS_WORKER_PORT")
      serverHost <- required(env, "MEESEEKS_SERVER_HOST")
      serverPort <- number(env, "MEESEEKS_SERVER_PORT")
      keystore <- required(env, "MEESEEKS_TLS_KEYSTORE")
      truststore <- required(env, "MEESEEKS_TLS_TRUSTSTORE")
      password <- required(env, "MEESEEKS_TLS_PASSWORD")
      lease <- optionalNumber(env, "MEESEEKS_LEASE_SECONDS", 60)
    yield WorkerConfig(
      host,
      port,
      serverHost,
      serverPort,
      Paths.get(keystore),
      Paths.get(truststore),
      password,
      env.getOrElse("MEESEEKS_SANDBOX_IMAGE", "meeseeks/brain:latest"),
      Paths.get(env.getOrElse("MEESEEKS_TASK_DIR", "./task")).toAbsolutePath,
      lease.seconds
    )

  private def required(env: Map[String, String], name: String): Either[String, String] =
    env.get(name).filter(_.nonEmpty).toRight(s"required env var $name is not set")

  private def number(env: Map[String, String], name: String): Either[String, Int] =
    required(env, name).flatMap(_.toIntOption.toRight(s"env var $name must be a number"))

  private def optionalNumber(
      env: Map[String, String],
      name: String,
      default: Int
  ): Either[String, Int] =
    if env.contains(name) then number(env, name) else Right(default)

end WorkerConfig

/** Pekko settings for a cluster node with Artery TLS and mutual authentication (FR21). */
def clusterTlsConfig(
    host: String,
    port: Int,
    seed: Address,
    keystore: Path,
    truststore: Path,
    password: String
): Config =
  def p(path: Path): String = path.toString.replace("\\", "/")
  ConfigFactory
    .parseString(s"""
      pekko {
        actor {
          provider = cluster
          allow-java-serialization = off
        }
        remote.artery {
          transport = tls-tcp
          canonical.hostname = "$host"
          canonical.port = $port
          ssl {
            ssl-engine-provider = org.apache.pekko.remote.artery.tcp.ConfigSSLEngineProvider
            config-ssl-engine {
              key-store            = "${p(keystore)}"
              key-store-password   = "$password"
              key-password         = "$password"
              trust-store          = "${p(truststore)}"
              trust-store-password = "$password"
              protocol             = "TLSv1.3"
              require-mutual-authentication = on
              hostname-verification = off
            }
          }
        }
        cluster {
          seed-nodes = ["$seed"]
          downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
          split-brain-resolver.active-strategy = keep-oldest
        }
      }
    """)
    .withFallback(ConfigFactory.load())

/** Pekko settings for a worker node. */
def workerPekkoConfig(c: WorkerConfig): Config =
  clusterTlsConfig(c.host, c.port, c.serverAddress, c.keystore, c.truststore, c.tlsPassword)
