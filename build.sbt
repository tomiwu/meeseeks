val pekkoV = "1.7.1"
val pekkoJdbcV = "1.3.0"
val kyoV = "1.0.0-RC7"
val h2V = "2.3.232"
val munitV = "1.0.3"

ThisBuild / scalaVersion := "3.10.0"
ThisBuild / crossScalaVersions := Seq("3.10.0", "3.9.0")
ThisBuild / organization := "io.meeseeks"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Werror",
  "-Wvalue-discard",
)

lazy val dumpClasspath = taskKey[Unit]("Write Test/fullClasspath to target/classpath.txt.")
lazy val makeCoverageDir = taskKey[Unit]("Create target/coverage so the forked JVM can write measurements.")

lazy val root = project
  .in(file("."))
  .aggregate(runtime, dev)
  .settings(
    name := "meeseeks",
    crossScalaVersions := Nil,
    publish / skip := true,
    // Root is an aggregation shell only; all sources live in submodules.
    Compile / unmanagedSourceDirectories := Nil,
    Test / unmanagedSourceDirectories := Nil,
  )

lazy val runtime = project
  .in(file("runtime"))
  .settings(
    name := "meeseeks-runtime",
    // Fork tests so each run gets an isolated JVM: prevents cluster ActorSystems
    // in one test run from interfering with those in a parallel or repeated run.
    Test / fork := true,
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-cluster-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoV,
      "org.apache.pekko" %% "pekko-persistence-jdbc" % pekkoJdbcV,
      "io.getkyo" %% "kyo-core" % kyoV,
      "io.getkyo" %% "kyo-config" % kyoV,
      "io.getkyo" %% "kyo-scheduler-pekko" % kyoV,
      "com.h2database" % "h2" % h2V,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoV % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoV % Test,
      "org.scalameta" %% "munit" % munitV % Test,
    ),
    makeCoverageDir := IO.createDirectory(baseDirectory.value / "target" / "coverage"),
    dumpClasspath := {
      val cp = (Test / fullClasspath).value
      val conv = fileConverter.value
      val out = baseDirectory.value / "target" / "classpath.txt"
      IO.createDirectory(out.getParentFile)
      IO.write(out, cp.map(a => conv.toPath(a.data).toAbsolutePath.toString).mkString(java.io.File.pathSeparator))
    },
  )

lazy val dev = project
  .in(file("dev"))
  .dependsOn(runtime)
  .settings(
    name := "meeseeks-dev",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitV % Test,
    ),
    makeCoverageDir := IO.createDirectory(baseDirectory.value / "target" / "coverage"),
    dumpClasspath := {
      val cp = (Test / fullClasspath).value
      val conv = fileConverter.value
      val out = baseDirectory.value / "target" / "classpath.txt"
      IO.createDirectory(out.getParentFile)
      IO.write(out, cp.map(a => conv.toPath(a.data).toAbsolutePath.toString).mkString(java.io.File.pathSeparator))
    },
  )

// crap: compile with coverage, run tests, dump classpath for crap4s.
// -coverage-out is scoped to this alias only and is not in the normal test job.
addCommandAlias(
  "crap",
  "; set ThisBuild / Compile / scalacOptions += \"-coverage-out:target/coverage\"; clean; runtime/makeCoverageDir; dev/makeCoverageDir; test; runtime/dumpClasspath; dev/dumpClasspath"
)
