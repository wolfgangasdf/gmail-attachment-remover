
import org.gradle.kotlin.dsl.support.zipTo
import org.openjfx.gradle.JavaFXOptions
import java.util.*

buildscript {
    repositories {
        mavenCentral()
    }
}

group = "com.sgar"
version = "1.0-SNAPSHOT"
val cPlatforms = listOf("mac-aarch64", "linux", "win") // compile for these platforms. "mac", "mac-aarch64", "linux", "win"
val needMajorJavaVersion = 21
val javaVersion = System.getProperty("java.version")!!
println("Current Java version: $javaVersion")
if (JavaVersion.current().majorVersion.toInt() != needMajorJavaVersion) throw GradleException("Use Java $needMajorJavaVersion")

println("Current Java version: ${JavaVersion.current()}")

plugins {
    scala
    id("idea")
    application
    id("com.github.ben-manes.versions") version "0.52.0"
    id("org.openjfx.javafxplugin") version "0.0.13"
    id("org.beryx.runtime") version "1.13.1"
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

application {
    mainClass.set("sgar.Sgar")
    applicationDefaultJvmArgs = listOf("-Dprism.verbose=true")
}

repositories {
    mavenCentral()
}

javafx {
    version = javaVersion
//    modules = listOf("javafx.base", "javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.media", "javafx.swing")
    modules = listOf("javafx.base", "javafx.controls", "javafx.media")
    // set compileOnly for crosspackage to avoid packaging host javafx jmods for all target platforms
    if (project.gradle.startParameter.taskNames.intersect(listOf("crosspackage", "dist")).isNotEmpty()) {
        configuration = "compileOnly"
    }
}
val javaFXOptions = the<JavaFXOptions>()

dependencies {
    implementation("org.scala-lang:scala-library:2.13.16")
    implementation("org.scalafx:scalafx_2.13:21.0.0-R32")
    implementation("jakarta.mail:jakarta.mail-api:2.1.3")
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    implementation("com.sun.mail:gimap:2.0.1")
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

    // sets targetPlatform JDK for host os from toolchain, for others (cross-package) from adoptium / jdkDownload
    // https://github.com/beryx/badass-runtime-plugin/issues/99
    // if https://github.com/gradle/gradle/issues/18817 is solved: use toolchain
    fun setTargetPlatform(jfxplatformname: String) {
        val platf = if (jfxplatformname == "win") "windows" else jfxplatformname // jfx expects "win" but adoptium needs "windows"
        val os = org.gradle.internal.os.OperatingSystem.current()
        var oss = if (os.isLinux) "linux" else if (os.isWindows) "windows" else if (os.isMacOsX) "mac" else ""
        if (oss == "") throw GradleException("unsupported os")
        if (System.getProperty("os.arch") == "aarch64") oss += "-aarch64"// https://github.com/openjfx/javafx-gradle-plugin#4-cross-platform-projects-and-libraries
        if (oss == platf) {
            targetPlatform(jfxplatformname, javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.parentFile.parentFile.absolutePath)
        } else { // https://api.adoptium.net/q/swagger-ui/#/Binary/getBinary
            targetPlatform(jfxplatformname) {
                val ddir = "${if (os.isWindows) "c:/" else "/"}tmp/jdk$javaVersion-$platf"
                println("downloading jdks to or using jdk from $ddir, delete folder to update jdk!")
                @Suppress("INACCESSIBLE_TYPE")
                setJdkHome(
                    jdkDownload("https://api.adoptium.net/v3/binary/latest/$needMajorJavaVersion/ga/$platf/x64/jdk/hotspot/normal/eclipse?project=jdk",
                        closureOf<org.beryx.runtime.util.JdkUtil.JdkDownloadOptions> {
                            downloadDir = ddir // put jdks here so different projects can use them!
                            archiveExtension = if (platf == "windows") "zip" else "tar.gz"
                        }
                    )
                )
            }
        }
    }
    cPlatforms.forEach { setTargetPlatform(it) }
}

open class CrossPackage : DefaultTask() {
    @Input var execfilename = "execfilename"
    @Input var macicnspath = "macicnspath"

    @TaskAction
    fun crossPackage() {
        File("${project.layout.buildDirectory.get().asFile.path}/crosspackage/").mkdirs()
        project.runtime.targetPlatforms.get().forEach { (t, _) ->
            println("targetplatform: $t")
            val imgdir = "${project.runtime.imageDir.get()}/${project.name}-$t"
            println("imagedir=$imgdir targetplatform=$t")
            when {
                t.startsWith("mac") -> {
                    val appp = File(project.layout.buildDirectory.get().asFile.path + "/crosspackage/$t/$execfilename.app").path
                    project.delete(appp)
                    project.copy {
                        into(appp)
                        from(macicnspath) {
                            into("Contents/Resources").rename { "$execfilename.icns" }
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
                          <string>${project.group}</string>
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
                    zipTo(File("${project.layout.buildDirectory.get().asFile.path}/crosspackage/$execfilename-$t.zip"), File("${project.layout.buildDirectory.get().asFile.path}/crosspackage/$t"))
                }
                t == "win" -> {
                    File("$imgdir/bin/$execfilename.bat").delete() // from runtime, not nice
                    val pf = File("$imgdir/$execfilename.bat")
                    pf.writeText("""
                        set JLINK_VM_OPTIONS=${project.application.applicationDefaultJvmArgs.joinToString(" ")}
                        set DIR=%~dp0
                        start "" "%DIR%\bin\javaw" %JLINK_VM_OPTIONS% -classpath "%DIR%/lib/*" ${project.application.mainClass.get()} 
                    """.trimIndent())
                    zipTo(File("${project.layout.buildDirectory.get().asFile.path}/crosspackage/$execfilename-$t.zip"), File(imgdir))
                }
                t.startsWith("linux") -> {
                    zipTo(File("${project.layout.buildDirectory.get().asFile.path}/crosspackage/$execfilename-$t.zip"), File(imgdir))
                }
            }
        }
    }
}

tasks.register<CrossPackage>("crosspackage") {
    dependsOn("runtime")
    execfilename = "GmailAttachmentRemover"
    macicnspath = "src/deploy/macosx/GmailAttachmentRemover.icns"
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
        cfg.incoming.files.forEach { f ->
            copy {
                from(f)
                into("${project.runtime.imageDir.get()}/${project.name}-$platform/lib")
            }
        }
    }
}

tasks.register("dist") {
    dependsOn("crosspackage")
    doLast {
        println("Deleting build/[image,jre,install]")
        project.delete(project.runtime.imageDir.get(), project.runtime.jreDir.get(), "${project.layout.buildDirectory.get().asFile.path}/install")
        println("Created zips in build/crosspackage")
    }
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase(Locale.getDefault()).contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}
tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    rejectVersionIf {
        isNonStable(candidate.version)
    }
    gradleReleaseChannel = "current"
}
