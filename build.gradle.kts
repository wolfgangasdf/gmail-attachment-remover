
import org.openjfx.gradle.JavaFXModule
import org.openjfx.gradle.JavaFXOptions

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
val cPlatforms = listOf("mac", "linux", "win") // compile for these platforms. "mac", "linux", "win"

println("Current Java version: ${JavaVersion.current()}")
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    if (JavaVersion.current().toString() != "13") throw GradleException("Use Java 13")
}

plugins {
    scala
    id("idea")
    application
    id("com.github.ben-manes.versions") version "0.27.0"
    id("org.openjfx.javafxplugin") version "0.0.8"
    id("org.beryx.runtime") version "1.8.0"
}

application {
    mainClassName = "sgar.Sgar"
    //defaultTasks = tasks.run
}

repositories {
    mavenCentral()
    jcenter()
}

javafx {
    modules = listOf("javafx.base", "javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.media", "javafx.swing", "javafx.web")
    // set compileOnly for crosspackage to avoid packaging host javafx jmods for all target platforms
    configuration = if (project.gradle.startParameter.taskNames.intersect(listOf("crosspackage", "dist")).isNotEmpty()) "compileOnly" else "compile"
}
val javaFXOptions = the<JavaFXOptions>()

dependencies {
    implementation("org.scala-lang:scala-library:2.13.1")
    compile("org.scalafx:scalafx_2.13:12.0.2-R18")
    compile("javax.mail:javax.mail-api:1.6.2")
    compile("com.sun.mail:javax.mail:1.6.2")
    compile("com.sun.mail:gimap:1.6.4")
    cPlatforms.forEach {platform ->
        val cfg = configurations.create("javafx_$platform")
        org.openjfx.gradle.JavaFXModule.getJavaFXModules(javaFXOptions.modules).forEach { m ->
            project.dependencies.add(cfg.name,"org.openjfx:${m.artifactName}:${javaFXOptions.version}:$platform")
        }
    }
}

runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    // check "gradle suggestModules", and add jdk.crypto.ec for ssl handshake
    modules.set(listOf("java.desktop", "jdk.unsupported", "jdk.jfr", "java.logging", "java.xml", "java.security.sasl", "java.datatransfer",
            "jdk.crypto.ec"))
    if (cPlatforms.contains("mac")) targetPlatform("mac", System.getenv("JDK_MAC_HOME"))
    if (cPlatforms.contains("win")) targetPlatform("win", System.getenv("JDK_WIN_HOME"))
    if (cPlatforms.contains("linux")) targetPlatform("linux", System.getenv("JDK_LINUX_HOME"))
}

open class CrossPackage : DefaultTask() {
    var execfilename = "execfilename"
    var macicnspath = "macicnspath" // name should be execfilename.icns

    @TaskAction
    fun crossPackage() {
        project.runtime.targetPlatforms.get().forEach { (t, _) ->
            println("targetplatform: $t")
            val imgdir = "${project.runtime.imageDir.get()}/${project.name}-$t"
            println("imagedir: $imgdir")
            when(t) {
                "mac" -> {
                    val appp = File(project.buildDir.path + "/crosspackage/mac/$execfilename.app").path
                    project.delete(appp)
                    project.copy {
                        into(appp)
                        from("$macicnspath/$execfilename.icns") {
                            into("Contents/Resources")
                        }
                        from("$imgdir/${project.application.executableDir}/${project.application.applicationName}") {
                            into("Contents/MacOS")
                        }
                        from(imgdir) {
                            into("Contents")
                        }
                    }
                    val pf = File("$appp/Contents/Info.plist")
                    pf.writeText("""
                        <?xml version="1.0" ?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                         <dict>
                          <key>LSMinimumSystemVersion</key>
                          <string>10.9</string>
                          <key>CFBundleDevelopmentRegion</key>
                          <string>English</string>
                          <key>CFBundleAllowMixedLocalizations</key>
                          <true/>
                          <key>CFBundleExecutable</key>
                          <string>$execfilename</string>
                          <key>CFBundleIconFile</key>
                          <string>$execfilename.icns</string>
                          <key>CFBundleIdentifier</key>
                          <string>main</string>
                          <key>CFBundleInfoDictionaryVersion</key>
                          <string>6.0</string>
                          <key>CFBundleName</key>
                          <string>${project.name}</string>
                          <key>CFBundlePackageType</key>
                          <string>APPL</string>
                          <key>CFBundleShortVersionString</key>
                          <string>${project.version}</string>
                          <key>CFBundleSignature</key>
                          <string>????</string>
                          <!-- See http://developer.apple.com/library/mac/#releasenotes/General/SubmittingToMacAppStore/_index.html
                               for list of AppStore categories -->
                          <key>LSApplicationCategoryType</key>
                          <string>Unknown</string>
                          <key>CFBundleVersion</key>
                          <string>100</string>
                          <key>NSHumanReadableCopyright</key>
                          <string>Copyright (C) 2019</string>
                          <key>NSHighResolutionCapable</key>
                          <string>true</string>
                         </dict>
                        </plist>
                    """.trimIndent())
                    // touch folder to update Finder
                    File(appp).setLastModified(System.currentTimeMillis())
                    // zip it
                    ant.withGroovyBuilder {
                        "zip"("destfile" to "${project.buildDir.path}/crosspackage/$execfilename-mac.zip",
                                "basedir" to "${project.buildDir.path}/crosspackage/mac") {
                        }
                    }
                }
                "win" -> {
                    ant.withGroovyBuilder {
                        "zip"("destfile" to "${project.buildDir.path}/crosspackage/$execfilename-win.zip",
                                "basedir" to imgdir) {
                        }
                    }
                }
                "linux" -> {
                    ant.withGroovyBuilder {
                        "zip"("destfile" to "${project.buildDir.path}/crosspackage/$execfilename-linux.zip",
                                "basedir" to imgdir) {
                        }
                    }
                }
            }
        }
    }
}

tasks.register<CrossPackage>("crosspackage") {
    dependsOn("runtime")
    execfilename = "GmailAttachmentRemover"
    macicnspath = "src/deploy/macosx"
}

tasks.withType(CreateStartScripts::class).forEach {script ->
    script.doFirst {
        script.classpath =  files("lib/*")
    }
}

// copy jmods for each platform
tasks["runtime"].doLast {
    cPlatforms.forEach { platform ->
        println("Copy jmods for platform $platform")
        val cfg = configurations["javafx_$platform"]
        cfg.resolvedConfiguration.files.forEach { f ->
            copy {
                from(f)
                into("${project.runtime.imageDir.get()}/${project.name}-$platform/lib")
            }
        }
    }
}

tasks {
    val dist by creating {
        dependsOn("crosspackage")
        doLast { println("Created GmailAttachmentRemover zips in build/dist") }
    }
}
