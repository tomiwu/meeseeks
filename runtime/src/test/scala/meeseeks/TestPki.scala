package meeseeks

import java.nio.file.Files
import java.nio.file.Path
import scala.sys.process.*

/** Generates a minimal test PKI using keytool.
  *
  * Creates a server cert, a valid worker cert, and a rogue worker cert (not trusted by the server).
  * All certs are self-signed. The server truststore accepts server and valid worker. The rogue
  * worker cert is not in the server truststore.
  */
object TestPki:

  val password: String = "testpass"

  final case class NodeCerts(keystorePath: Path, truststorePath: Path)

  final case class PkiBundle(
      server: NodeCerts,
      validWorker: NodeCerts,
      rogueWorker: NodeCerts
  )

  def setup(): PkiBundle =
    val dir = Files.createTempDirectory("meeseeks-test-pki-")

    genKeypair(dir, "server")
    genKeypair(dir, "worker")
    genKeypair(dir, "rogue")

    exportCert(dir, "server")
    exportCert(dir, "worker")
    exportCert(dir, "rogue")

    val serverTrust = dir.resolve("server-trust.p12")
    importCert(serverTrust, "server", dir.resolve("server.crt"))
    importCert(serverTrust, "worker", dir.resolve("worker.crt"))

    val workerTrust = dir.resolve("worker-trust.p12")
    importCert(workerTrust, "server", dir.resolve("server.crt"))

    val rogueTrust = dir.resolve("rogue-trust.p12")
    importCert(rogueTrust, "server", dir.resolve("server.crt"))

    PkiBundle(
      server = NodeCerts(dir.resolve("server.p12"), serverTrust),
      validWorker = NodeCerts(dir.resolve("worker.p12"), workerTrust),
      rogueWorker = NodeCerts(dir.resolve("rogue.p12"), rogueTrust)
    )

  private def genKeypair(dir: Path, alias: String): Unit =
    run(
      Seq(
        "keytool",
        "-genkeypair",
        "-alias",
        alias,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "1",
        "-dname",
        s"CN=$alias",
        "-keystore",
        dir.resolve(s"$alias.p12").toString,
        "-storetype",
        "PKCS12",
        "-storepass",
        password,
        "-keypass",
        password
      )
    )

  private def exportCert(dir: Path, alias: String): Unit =
    run(
      Seq(
        "keytool",
        "-exportcert",
        "-alias",
        alias,
        "-keystore",
        dir.resolve(s"$alias.p12").toString,
        "-storepass",
        password,
        "-file",
        dir.resolve(s"$alias.crt").toString,
        "-rfc"
      )
    )

  private def importCert(truststore: Path, alias: String, certFile: Path): Unit =
    run(
      Seq(
        "keytool",
        "-importcert",
        "-noprompt",
        "-alias",
        alias,
        "-keystore",
        truststore.toString,
        "-storetype",
        "PKCS12",
        "-storepass",
        password,
        "-file",
        certFile.toString
      )
    )

  private def run(cmd: Seq[String]): Unit =
    val logger = ProcessLogger(_ => (), _ => ())
    val exitCode = cmd.!(logger)
    require(exitCode == 0, s"keytool command failed (exit $exitCode): ${cmd.mkString(" ")}")
