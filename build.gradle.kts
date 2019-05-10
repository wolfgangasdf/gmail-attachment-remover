
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

buildscript {
    repositories {
        mavenCentral()
        jcenter()
    }
}

// todo
// javaOptions ++= Seq("-Xms100m", "-Xmx300m")

group = "com.sgar"
version = "1.0-SNAPSHOT"

plugins {
    scala
    id("idea")
    id("application")
    id("com.github.ben-manes.versions") version "0.20.0"
    id("com.github.johnrengelman.shadow") version "4.0.3"
    id("edu.sc.seis.macAppBundle") version "2.3.0"
}

tasks.withType<Wrapper> {
    gradleVersion = "5.4.1"
}

application {
    mainClassName = "sgar.Sgar"
    //defaultTasks = tasks.run
}

tasks.withType<Jar> {
    manifest {
        attributes(mapOf(
                "Description" to "GmailAttachmentRemover JAR",
                "Implementation-Title" to "GmailAttachmentRemover",
                "Implementation-Version" to version,
                "Main-Class" to "sgar.Sgar"
        ))
    }
}

tasks.withType<ShadowJar> {
    // uses manifest from above!
    baseName = "GmailAttachmentRemover"
    classifier = ""
    version = ""
    mergeServiceFiles() // can be essential
}

macAppBundle {
    mainClassName = "sgar.Sgar"
    appName = "GmailAttachmentRemover"
    icon = "src/deploy/macosx/icon.icns"
    bundleJRE = false
}

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation("org.scala-lang:scala-library:2.12.8")
    compile("org.scalafx:scalafx_2.12:8.0.181-R13")
//    compile("org.scala-lang.modules:scala-parser-combinators_2.12:1.1.1")
    compile("javax.mail:javax.mail-api:1.6.2")
    compile("com.sun.mail:javax.mail:1.6.2")
    compile("com.sun.mail:gimap:1.6.2")
}

tasks {
    val zipmac by creating(Zip::class) {
        dependsOn("createApp") // macappbundle
        archiveName = "GmailAttachmentRemover-mac.zip"
        destinationDir = file("$buildDir/dist")
        from("$buildDir/macApp")
        include("GmailAttachmentRemover.app/")
    }
    val zipjar by creating(Zip::class) {
        dependsOn(shadowJar)
        archiveName = "GmailAttachmentRemover-winlinux.zip"
        destinationDir = file("$buildDir/dist")
        from("$buildDir/libs")
        include("GmailAttachmentRemover.jar")
    }

    val zipchrome by creating(Zip::class) {
        archiveName = "GmailAttachmentRemover-chromeextension.zip"
        destinationDir = file("$buildDir/dist")
        from("extensions/chrome")
    }
    val dist by creating {
        dependsOn(zipmac)
        dependsOn(zipjar)
        dependsOn(zipchrome)
        doLast { println("Created GmailAttachmentRemover zips in build/dist") }
    }
}
