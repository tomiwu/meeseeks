package meeseeks.sandbox

import kyo.*
import meeseeks.port.Sandbox
import meeseeks.port.SandboxError
import meeseeks.port.SandboxResult

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import scala.jdk.CollectionConverters.*

/** Real Podman sandbox adapter.
  *
  * Each call to run:
  *   - starts a fresh container (--rm)
  *   - mounts only the task directory read-only at config.workDir
  *   - makes the root filesystem read-only (--read-only)
  *   - injects at most one credential via a temporary env file; the file is deleted after the
  *     container exits
  */
final class PodmanSandbox(config: SandboxConfig) extends Sandbox:

  override def run(command: String): SandboxResult < (Sync & Abort[SandboxError]) =
    Sync.defer:
      val envFilePath: Option[java.nio.file.Path] = config.credential.map(writeEnvFile)
      try
        val args = buildArgs(command, envFilePath.map(_.toString))
        val pb = new ProcessBuilder(args.asJava)
        val _ = pb.redirectErrorStream(false)
        try
          val process = pb.start()
          val stdoutBuf = new ByteArrayOutputStream()
          val stderrBuf = new ByteArrayOutputStream()
          val stdoutThread = Thread { () =>
            val _ = process.getInputStream.transferTo(stdoutBuf)
          }
          val stderrThread = Thread { () =>
            val _ = process.getErrorStream.transferTo(stderrBuf)
          }
          stdoutThread.start()
          stderrThread.start()
          val exitCode = process.waitFor()
          stdoutThread.join()
          stderrThread.join()
          SandboxResult(exitCode, stdoutBuf.toString("UTF-8"), stderrBuf.toString("UTF-8"))
        catch
          case e: java.io.IOException =>
            Abort.fail(SandboxError.Unavailable(s"podman start failed: ${e.getMessage}"))
          case _: InterruptedException =>
            Abort.fail(SandboxError.Timeout("container interrupted"))
      finally envFilePath.foreach(p => { val _ = Files.deleteIfExists(p) })

  private def buildArgs(command: String, envFile: Option[String]): List[String] =
    val envArgs = envFile.fold(List.empty[String])(path => List(s"--env-file=$path"))
    List(
      "podman",
      "run",
      "--rm",
      "--read-only",
      s"--volume=${config.taskDir.toAbsolutePath}:${config.workDir}:ro",
      s"--workdir=${config.workDir}"
    ) ++ envArgs ++ List(config.image) ++ splitCommand(command)

  private def writeEnvFile(cred: Credential): java.nio.file.Path =
    val tmp = Files.createTempFile("sandbox-cred-", ".env")
    val ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    val _ = Files.setPosixFilePermissions(tmp, ownerOnly)
    val _ = Files.writeString(tmp, cred.toEnvLine)
    tmp

  private def splitCommand(command: String): List[String] =
    command.split("\\s+").toList.filter(_.nonEmpty)

end PodmanSandbox
