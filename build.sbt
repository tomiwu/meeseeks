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
  )

lazy val dev = project
  .in(file("dev"))
  .dependsOn(runtime)
  .settings(
    name := "meeseeks-dev",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitV % Test,
    ),
  )

// crap: cross-build test (proves both Scala versions pass).
addCommandAlias("crap", "+test")
