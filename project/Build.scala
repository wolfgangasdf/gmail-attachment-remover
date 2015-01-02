import sbt._
import sbt.Keys._

//import no.vedaadata.sbtjavafx.JavaFXPlugin
//import no.vedaadata.sbtjavafx.JavaFXPlugin.JFX


object Build extends Build {

  lazy val sgar = Project(
    id = "sgar",
    base = file("."),
  /* should work but doesn't (sbt run can't find main class), use build.sbt for now:
    settings = Defaults.coreDefaultSettings ++ JavaFXPlugin.jfxSettings ++ Seq(
      JFX.mainClass := Some("sgar.Sgar"),
   */
    settings = Defaults.coreDefaultSettings ++ Seq(
      name := "Gmail Attachment Remover",
      organization := "sgar",
      version := "0.9-SNAPSHOT",
      scalaVersion := "2.11.4",
      scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation"),
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
