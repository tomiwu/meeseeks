ThisBuild / scalaVersion := "3.10.0"
ThisBuild / crossScalaVersions := Seq("3.10.0", "3.9.0")
ThisBuild / organization := "io.meeseeks"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-feature"
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "meeseeks",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.3" % Test
  )
