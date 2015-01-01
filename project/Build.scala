import sbt._
import sbt.Keys._

import no.vedaadata.sbtjavafx.JavaFXPlugin
import no.vedaadata.sbtjavafx.JavaFXPlugin.JFX


object Build extends Build {

  lazy val sgar = Project(
    id = "sgar",
    base = file("."),
  /* should work but doesn't (sbt run can't find main class):
    settings = Defaults.coreDefaultSettings ++ JavaFXPlugin.jfxSettings ++ Seq(
      JFX.mainClass := Some("sgar.Sgar"),
   */
    settings = Defaults.coreDefaultSettings ++ Seq(
      name := "sgar",
      organization := "com.sgar",
      version := "0.1-SNAPSHOT",
      scalaVersion := "2.11.4",
      libraryDependencies ++= Seq(
        "org.scalafx" %% "scalafx" % "8.0.20-R6",
        "com.sun.mail" % "javax.mail" % "1.5.2",
        "com.sun.mail" % "gimap" % "1.5.2",
        "org.scalaj" %% "scalaj-http" % "1.1.0",
        "com.googlecode.json-simple" % "json-simple" % "1.1.1"
      )
    )
  )
}
