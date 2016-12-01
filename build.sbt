import java.time.ZonedDateTime

name := "sgar"
organization := "com.sgar"
version := "0.1-SNAPSHOT"
javaOptions ++= Seq("-Xms100m", "-Xmx300m")
scalaVersion := "2.11.8"
scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation", "-encoding", "UTF-8")

libraryDependencies ++= Seq(
  "org.scalafx" %% "scalafx" % "8.0.102-R11",
  "com.sun.mail" % "javax.mail" % "1.5.6",
  "com.sun.mail" % "gimap" % "1.5.6"
)

lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin).
  settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion,
      BuildInfoKey.action("buildTime") { ZonedDateTime.now.toString }
    ),
    buildInfoPackage := "buildinfo",
    buildInfoUsePackageAsPath := true
  )

////////////////// sbt-javafx for packaging
jfxSettings
JFX.verbose := true
JFX.mainClass := Some("sgar.Sgar")
JFX.devKit := JFX.jdk(System.getenv("JAVA_HOME"))
JFX.pkgResourcesDir := baseDirectory.value + "/src/deploy"
JFX.artifactBaseNameValue := "GmailAttachmentRemover"

/////////////// mac app bundle via sbt-appbundle
Seq(appbundle.settings: _*)
appbundle.name := "GmailAttachmentRemover"
appbundle.javaVersion := "1.8*"
appbundle.icon := Some(file("src/deploy/macosx/sgar.icns"))
appbundle.mainClass := JFX.mainClass.value
appbundle.executable := file("src/deploy/macosx/universalJavaApplicationStub")

/////////////// task to zip the jar for win,linux
lazy val tzip = TaskKey[Unit]("zip")
tzip := {
  println("packaging...")
  JFX.packageJavaFx.value
  println("zipping jar & libs...")
  val s = target.value + "/" + JFX.artifactBaseNameValue.value + "-win-linux.zip"
  IO.zip(
    Path.allSubpaths(new File(crossTarget.value + "/" + JFX.artifactBaseNameValue.value)).
      filterNot(_._2.endsWith(".html")).filterNot(_._2.endsWith(".jnlp")), new File(s))
  println("==> created windows & linux zip: " + s)
}

/////////////// task to zip the mac app bundle
lazy val tzipmac = TaskKey[Unit]("zipmac")
tzipmac := {
  println("making app bundle...")
  appbundle.appbundle.value
  println("zipping mac app bundle...")
  val zf = new File(target.value + "/" + appbundle.name.value + "-mac.zip")
  val bd = new File(target.value + "/" + appbundle.name.value + ".app")
  def entries(f: File):List[File] = f :: (if (f.isDirectory) IO.listFiles(f).toList.flatMap(entries) else Nil)
  IO.zip(entries(bd).map(d => (d, d.getAbsolutePath.substring(bd.getParent.length))), zf)
  println("==> created mac app zip: " + zf)
}

/////////////// task to do all at once
lazy val tdist = TaskKey[Unit]("dist")
tdist := {
  tzipmac.value
  tzip.value
  println("Created Sgar distribution files!")
}

