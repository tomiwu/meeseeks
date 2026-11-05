package meeseeks.build

import kyo.*

/** Port: run a check command in an isolated container and return pass or fail. */
trait CheckRunner:
  def run(command: String): CheckResult < (Sync & Abort[CheckError])
