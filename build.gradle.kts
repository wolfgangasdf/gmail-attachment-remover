
import org.gradle.kotlin.dsl.support.zipTo
import org.openjfx.gradle.JavaFXOptions
import java.net.URI
import java.util.*

buildscript {
    repositories {
        mavenCentral()
    }
}

group = "com.sgar"
version = "1.0-SNAPSHOT"
val crossPlatforms = listOf("mac-aarch64", "windows-x64", "linux-x64") // create apps for these platforms [linux|mac|windows] - architectures [x86|x64|aarch64], see adoptium parameters https://api.adoptium.net/q/swagger-ui/#/Binary/getBinary
val needMajorJavaVersion = 25
val javaVersion = System.getProperty("java.version")!!
println("Current Java version: $javaVersion")
if (JavaVersion.current().majorVersion.toInt() != needMajorJavaVersion) throw GradleException("Use Java $needMajorJavaVersion")

plugins {
    scala
    id("idea")
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.github.ben-manes.versions") version "0.53.0"
    id("org.beryx.runtime") version "2.0.1"
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

application {
    mainClass.set("sgar.Sgar")
    applicationDefaultJvmArgs = listOf("-Dprism.verbose=true", "--enable-native-access=javafx.graphics")
}

repositories {
    mavenCentral()
}

javafx {
    version = needMajorJavaVersion.toString()
    modules = listOf("javafx.base", "javafx.controls", "javafx.media", "javafx.graphics")
    // set compileOnly for crosspackage to avoid packaging host javafx jmods for all target platforms
    if (project.gradle.startParameter.taskNames.intersect(listOf("crosspackage", "dist")).isNotEmpty()) {
        configuration = "compileOnly"
    }
}
val javaFXOptions = the<JavaFXOptions>()

dependencies {
    implementation("org.scala-lang:scala-library:2.13.18")
    implementation("org.scalafx:scalafx_2.13:24.0.2-R36") {
        exclude("org.openjfx") // otherwise image includes host jars, possibly 24<>25 issue
    }
    implementation("jakarta.mail:jakarta.mail-api:2.1.5")
    implementation("org.eclipse.angus:angus-mail:2.0.5")
    implementation("com.sun.mail:gimap:2.0.2")

    addJavafxDependencies()
}

runtime {
    options.set(listOf("--strip-debug", "--compress", "zip-6", "--no-header-files", "--no-man-pages"))
    // check "gradle suggestModules", and add jdk.crypto.ec for ssl handshake
    modules.set(listOf("java.desktop", /*"jdk.unsupported", */"jdk.jfr", "java.logging", "java.xml", "java.security.sasl", /*"java.datatransfer",*/
            "jdk.crypto.ec"))
    targetPlatforms.empty()
    crossPlatforms.forEach {
        targetPlatform(it, downloadJdkWithJmods(it))
    }
}

tasks.register<CrossPackage>("crosspackage") {
    dependsOn("runtime")
    execfilename = "GmailAttachmentRemover"
    macicnspath = "src/deploy/macosx/GmailAttachmentRemover.icns"
}

tasks.register("dist") {
    dependsOn("crosspackage")
    doLast {
        println("Deleting build/[image,jre,install]")
        project.delete(project.runtime.imageDir.get(), project.runtime.jreDir.get(), "${project.layout.buildDirectory.get().asFile.path}/install")
        println("Created zips in build/crosspackage")
    }
}

/////////////////////////////////////////////////////////////////////////////////////
///////////////// crosspackage helpers jdk 25 v1
/////////////////////////////////////////////////////////////////////////////////////

fun crossPlatformToJfxTarget(cp: String): String {
    return when(cp) { // https://github.com/openjfx/javafx-gradle-plugin?tab=readme-ov-file#4-cross-platform-projects-and-libraries
        "linux-x86", "linux-x64" -> "linux"
        "linux-aarch64" -> "linux-aarch64"
        "windows-x86", "windows-x64" -> "win"
        "mac-x86", "mac-x64" -> "mac"
        "mac-aarch64" -> "mac-aarch64"
        else -> throw GradleException("platformSpecToJfxTarget not supported: $cp")
    }
}
fun crossPlatformToList(cp: String): List<String> {
    return cp.split("-")
}
fun downloadExtract(ddir: File, zip: Boolean, dropPaths: Int, durl: String) {
    println(" downloadExtract: $ddir, $zip, $dropPaths, $durl")
    ddir.mkdirs()
    val dfile = File(ddir.absolutePath + "/tmpfile.${if (zip) "zip" else "tar.gz"}")
    println("  download $durl ...")
    URI(durl).toURL().openStream().use { input -> dfile.outputStream().use { output -> input.copyTo(output) } }
    println("  downloaded to ${dfile.absolutePath}, extracting...")
    copy {
        from((if (zip) zipTree(dfile) else tarTree(dfile))).into(ddir).eachFile { // drop first folder
            relativePath = RelativePath(true, *relativePath.segments.drop(dropPaths).toTypedArray())
        }.includeEmptyDirs = false
    }
    dfile.delete()
}
fun downloadJdkWithJmods(platformSpec: String): String {
    println("downloadJdkWithJmods $platformSpec ...")
    val os = org.gradle.internal.os.OperatingSystem.current()
    val psl = crossPlatformToList(platformSpec)
    val ddir = File("${if (os.isWindows) "c:/" else "/"}tmp/jdkjmod-$needMajorJavaVersion-${psl[0]}-${psl[1]}")
    val zip = (psl[0] == "windows")
    if (ddir.exists()) {
        println("  not downloading, delete folder to re-download/update: ${ddir.absolutePath}")
        return ddir.absolutePath
    }
    downloadExtract(ddir, zip,1, "https://api.adoptium.net/v3/binary/latest/$needMajorJavaVersion/ga/${psl[0]}/${psl[1]}/jdk/hotspot/normal/eclipse?project=jdk")
    downloadExtract(File(ddir.absolutePath + "/jmods"), zip,1, "https://api.adoptium.net/v3/binary/latest/$needMajorJavaVersion/ga/${psl[0]}/${psl[1]}/jmods/hotspot/normal/eclipse?project=jdk")
    return ddir.absolutePath
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
                t.startsWith("win") -> {
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
                    println("NOTE: linux zip might need chmod -R a+x")
                }
            }
        }
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

// copy jmods for each platform. note that the build host jmods are still around, not worth removing them.
tasks["runtime"].doLast {
    crossPlatforms.forEach { platform ->
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

fun addJavafxDependencies() {
    crossPlatforms.forEach { cp ->
        val cfg = configurations.create("javafx_$cp")
        org.openjfx.gradle.JavaFXModule.getJavaFXModules(javaFXOptions.modules).forEach { m ->
            project.dependencies.add(cfg.name,"org.openjfx:${m.artifactName}:${javaFXOptions.version}:${crossPlatformToJfxTarget(cp)}")
        }
        // fix this https://github.com/openjfx/javafx-gradle-plugin?tab=readme-ov-file#variants
        cfg.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, Usage.JAVA_RUNTIME))
        cfg.attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(OperatingSystemFamily::class, "mac"/*platform.osFamily*/))
        cfg.attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named(MachineArchitecture::class, "aarch64"/*platform.arch*/))
    }
}

tasks.withType(CreateStartScripts::class).forEach {script ->
    script.doFirst {
        script.classpath =  files("lib/*")
    }
}

