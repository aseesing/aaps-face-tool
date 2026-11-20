// The WFF converter and its tests. The converter (src/main) writes the watchface.xml of a design;
// the harness in the tests bakes its pictures with our engine, packs the APK with Pack, validates
// it, and the parity suite compares it with our engine. See README.md.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean
import java.util.concurrent.TimeUnit

// The same Android and Kotlin Gradle plugins as AAPS
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradlePlugin)
    }
}

plugins {
    id("com.android.library") version libs.versions.gradlePlugin.get()
}

val aapsDir = gradle.extra["aapsDir"] as File
val designsDir = if (gradle.extra.has("designsDir")) gradle.extra["designsDir"] as File else null

android {
    namespace = "app.aaps.facetool"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("aaps:cwfrender")
    implementation("aaps:shared-impl")
    implementation("aaps:core-interfaces")
    implementation("aaps:core-data")
    implementation(libs.androidx.core)

    testImplementation(libs.androidx.wear.watchface.complications.data)
    testImplementation(libs.androidx.wear.watchface.complications.rendering)
    testImplementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.junit)
    testImplementation(libs.org.junit.jupiter)
    testImplementation(libs.org.robolectric)
    testImplementation(libs.com.google.truth)
    testImplementation(libs.org.mockito.kotlin)
    // org.json for the plain JVM tests: AOSP's own, as in the AAPS tests
    testImplementation(libs.org.json.android)
    // Checks the Watch Face Format faces, as the AAPS wear build checks its own faces
    testImplementation(libs.com.google.watchface.validator.push.cli)
    testRuntimeOnly(libs.org.junit.vintage.engine)
    testRuntimeOnly(libs.org.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The tests write their output relative to this folder
    workingDir = projectDir
    // The tests read a picture of AAPS (the moon photo of the watch)
    systemProperty("aaps.dir", aapsDir.path)
    maxHeapSize = "3g"
    // The faces render on the CPU, one core per test JVM: run a test JVM on every core but one, as
    // many as fit in the memory (a test JVM uses about 1 to 3 GB)
    val memoryGb = (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean)
        .totalMemorySize / (1L shl 30)
    maxParallelForks = minOf(Runtime.getRuntime().availableProcessors() - 1, (memoryGb / 3).toInt()).coerceAtLeast(1)
    // The WFF harness (src/test/kotlin/app/aaps/facetool/wff, see docs/wff-apk.md): -PwffOut=... and so on
    mapOf("wffZips" to "wff.zips", "wffOut" to "wff.out").forEach { (property, name) -> providers.gradleProperty(property).orNull?.let { systemProperty(name, file(it).absolutePath) } }
    if (!providers.gradleProperty("wffZips").isPresent) designsDir?.let { systemProperty("wff.zips", File(it, "cwf-designs").path) }
    providers.gradleProperty("wffClient").orNull?.let { systemProperty("wff.client", it) }
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// The parity suite of CWF and WFF (src/test/kotlin/app/aaps/facetool/wffparity, docs/wff-parity.md):
// one small design per feature, converted and compared with our engine. Too slow for every test run,
// so it is not in testDebugUnitTest.
//   ./gradlew wffParityTest                         converts, validates and checks the lint
//   ./gradlew wffParityTest -PwffParityEmulator      also on the Wear OS 6 emulator
//   -PwffParityOnly=ShapesText,ClassicFace.secondView   only these groups or tests (or --tests "...")
// With the emulator, one driver (tools/wff-parity-emulator.sh) shows the faces that the tests queue
// while the tests still run, and wffParityCompare then compares its pictures with ours.
val parityDrivers = ArrayList<Process>()
afterEvaluate {
    val base = tasks.named<Test>("testDebugUnitTest")
    base.configure { filter { excludeTestsMatching("app.aaps.facetool.wffparity.*") } }
    val emulator = providers.gradleProperty("wffParityEmulator").isPresent
    val root = layout.buildDirectory.dir("wff-parity").get().asFile
    val queue = File(root, "queue.txt")
    val done = File(root, "queue.done")
    val wearApk = providers.gradleProperty("wffParityWearApk").orNull?.let { file(it) } ?: File(aapsDir, "wear/build/outputs/apk/full/debug/wear-full-debug.apk")
    fun Test.likeBase() {
        val b = base.get()
        classpath = b.classpath
        testClassesDirs = b.testClassesDirs
        javaLauncher.set(b.javaLauncher)
        jvmArgumentProviders.addAll(b.jvmArgumentProviders)
        b.systemProperties.forEach { (key, value) -> if (key !in systemProperties) systemProperty(key, value as Any) }
    }
    val compare = tasks.register<Test>("wffParityCompare") {
        group = "verification"
        description = "Compares the emulator pictures of the parity suite with our engine"
        likeBase()
        systemProperty("wff.parity.queue", queue.path)
        filter {
            includeTestsMatching("*ParityEmulatorCheck*")
            isFailOnNoMatchingTests = false
        }
        doFirst {
            // The tests are done: the driver finishes the queue and stops
            done.writeText("done")
            for (driver in parityDrivers) {
                if (!driver.waitFor(60, TimeUnit.MINUTES)) driver.destroy()
                logger.lifecycle("Emulator driver ended (${driver.exitValue()}), see ${root}/emulator-*.log")
            }
            parityDrivers.clear()
        }
        doLast { logger.lifecycle("Parity summary: ${File(root, "summary.html")}") }
    }
    tasks.register<Test>("wffParityTest") {
        group = "verification"
        description = "Compares the WFF face of each feature of the layer engine with our own engine"
        likeBase()
        systemProperty("wff.parity", "true")
        providers.gradleProperty("wffParityWait").orNull?.let { systemProperty("wff.parity.wait", it) }
        filter {
            val only = providers.gradleProperty("wffParityOnly").orNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (only.isNullOrEmpty()) includeTestsMatching("app.aaps.facetool.wffparity.*")
            else only.forEach { part ->
                val (group, test) = if ("." in part) part.substringBefore(".") to part.substringAfter(".") else part to null
                // A group is the start of the class names: ClassicFace takes ParityClassicFaceClockTest and the others
                includeTestsMatching("app.aaps.facetool.wffparity.Parity${group.removePrefix("Parity").removeSuffix("Test")}*" + (test?.let { ".$it" } ?: ""))
            }
            excludeTestsMatching("*ParityEmulatorCheck*")
        }
        // A feature group per JVM, so a test never draws with what another one loaded before it
        forkEvery = 1
        // With the emulator, fewer test JVMs: the emulators need CPU too, and they are the slower part
        maxParallelForks = if (emulator) minOf(base.get().maxParallelForks, 6) else base.get().maxParallelForks
        if (emulator) {
            systemProperty("wff.parity.queue", queue.path)
            finalizedBy(compare)
        }
        doFirst {
            root.mkdirs()
            queue.delete()
            done.delete()
            if (emulator) {
                // The pictures of the emulator must come from the wear app of this AAPS checkout
                check(wearApk.isFile) { "No wear app at $wearApk: build it in AAPS with :wear:assembleFullDebug, or give -PwffParityWearApk=..." }
                val newest = File(aapsDir, "wear/src/main").walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
                check(wearApk.lastModified() >= newest) { "The wear app $wearApk is older than its sources: build it again in AAPS with :wear:assembleFullDebug" }
                // One driver per running Wear OS 6 emulator (API 36 or newer: the suite's faces are WFF 4);
                // they share the queue. A Wear OS 5 emulator is left alone. A real watch is never an emulator.
                val adb = File(System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk", "platform-tools/adb").takeIf { it.canExecute() }?.path ?: "adb"
                fun api(device: String) = ProcessBuilder(adb, "-s", device, "shell", "getprop", "ro.build.version.sdk").start().inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
                val devices = ProcessBuilder(adb, "devices").start().inputStream.bufferedReader().readLines()
                    .mapNotNull { Regex("^(emulator-\\d+)\\s+device$").find(it.trim())?.groupValues?.get(1) }
                    .filter { api(it) >= 36 }
                check(devices.isNotEmpty()) { "No emulator runs: start a Wear OS 6 emulator (docs/wff-parity.md)" }
                devices.forEachIndexed { k, device ->
                    val log = File(root, "emulator-$device.log")
                    parityDrivers += ProcessBuilder("sh", "tools/wff-parity-emulator.sh", queue.path, done.path, wearApk.path).directory(projectDir)
                        .apply {
                            environment()["WFF_DEVICE"] = device
                            environment()["WFF_SHARD"] = "$k/${devices.size}"
                            // -PwffParityFresh: take every picture again, also of faces that did not change
                            if (providers.gradleProperty("wffParityFresh").isPresent) environment()["WFF_FRESH"] = "1"
                        }
                        .redirectErrorStream(true).redirectOutput(log).start()
                    logger.lifecycle("Emulator driver on $device started, log: $log")
                }
            }
        }
    }
}

// The WFF faces (-PwffOut=..., see docs/wff-apk.md): each design zip in its own JVM,
// so a design never draws with a font that another design loaded before it. -PwffOnly=<zip name> makes one.
if (providers.gradleProperty("wffOut").isPresent) {
    val zipDir = providers.gradleProperty("wffZips").orNull?.let { file(it) } ?: designsDir?.let { File(it, "cwf-designs") } ?: error("Give -PwffZips=<folder with zips>, or set designsDir (the aaps-watchfaces checkout) in local.properties")
    val only = providers.gradleProperty("wffOnly").orNull
    val zipNames = zipDir.listFiles { f -> f.extension == "zip" }?.map { it.name }?.sorted().orEmpty().filter { only == null || it == only || it.removeSuffix(".zip") == only }
    afterEvaluate {
        val base = tasks.named<Test>("testDebugUnitTest")
        val perZip = zipNames.mapIndexed { index, zip ->
            tasks.register<Test>("wffDesign$index") {
                description = "Makes the WFF face of $zip in its own JVM"
                val b = base.get()
                classpath = b.classpath
                testClassesDirs = b.testClassesDirs
                javaLauncher.set(b.javaLauncher)
                jvmArgumentProviders.addAll(b.jvmArgumentProviders)
                b.systemProperties.forEach { (key, value) -> if (key !in systemProperties) systemProperty(key, value as Any) }
                systemProperty("wff.only", zip)
                filter { includeTestsMatching("*WffApkTest*") }
            }
        }
        base.configure {
            dependsOn(perZip)
            systemProperty("wff.only", "-")
            filter { includeTestsMatching("*WffApkTest*") }
        }
    }
}

