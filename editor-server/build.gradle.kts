// The render server of the face editor: one JVM that runs the AAPS engine under Robolectric native
// graphics and answers the commands of facetool over HTTP on 127.0.0.1. See docs/face-editor-design.md.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

val aapsDir = gradle.extra["aapsDir"] as File
val designsDir = if (gradle.extra.has("designsDir")) gradle.extra["designsDir"] as File else null

android {
    namespace = "app.aaps.facetool.editor.server"
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
    // The converter and the harness
    implementation(project(":"))
    implementation(project(":editor-model"))
    implementation("aaps:cwfrender")
    implementation("aaps:shared-impl")
    implementation("aaps:core-interfaces")
    implementation("aaps:core-data")
    implementation(libs.androidx.core)

    // The server runs as a Robolectric test that does not end (EditorServerMain), so the engine's
    // runtime is the test runtime, as in the root project
    testImplementation(libs.androidx.wear.watchface.complications.data)
    testImplementation(libs.androidx.wear.watchface.complications.rendering)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.junit)
    testImplementation(libs.org.junit.jupiter)
    testImplementation(libs.org.robolectric)
    testImplementation(libs.com.google.truth)
    testImplementation(libs.org.mockito.kotlin)
    testImplementation(libs.com.google.watchface.validator.push.cli)
    testRuntimeOnly(libs.org.junit.vintage.engine)
    testRuntimeOnly(libs.org.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    workingDir = rootDir
    systemProperty("aaps.dir", aapsDir.path)
    systemProperty("editor.checkout", rootDir.path)
    maxHeapSize = "3g"
    designsDir?.let { systemProperty("wff.zips", File(it, "cwf-designs").path) }
    // -PeditorGoldenWrite=<start of the file names, or all> writes those golden files of the server tests new
    providers.gradleProperty("editorGoldenWrite").orNull?.let {
        systemProperty("editor.golden.write", it)
        outputs.upToDateWhen { false }
    }
    inputs.dir("src/test/golden").withPropertyName("golden")
    // A test class per JVM: a design must never draw with what another test loaded before it
    forkEvery = 1
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 6)
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// The server is started without Gradle (design 4.3):
//   ./gradlew :editor-server:editorClasspath   writes editor-server/build/editor/classpath.txt and java.txt
//   $(cat editor-server/build/editor/java.txt) @editor-server/build/editor/classpath.txt -Deditor.serve=true \
//       -Deditor.checkout=<this repository> org.junit.runner.JUnitCore app.aaps.facetool.editor.server.EditorServerMain
// facetool does this by itself (tools/facetool).
afterEvaluate {
    val base = tasks.named<Test>("testDebugUnitTest")
    tasks.register("editorClasspath") {
        group = "editor"
        description = "Writes the Java arguments that start the editor's render server without Gradle"
        val classpath = base.get().classpath
        val jvmArgs = provider { base.get().allJvmArgs }
        val out = layout.buildDirectory.file("editor/classpath.txt")
        // The Java of the tests (JDK 21): Robolectric does not read the classes of a much newer Java
        val java = layout.buildDirectory.file("editor/java.txt")
        // The server compares this with its own start time, to say when it is older than the build
        val stamp = layout.buildDirectory.file("editor/stamp.txt")
        val launcher = base.get().javaLauncher
        inputs.files(classpath)
        outputs.files(out, java, stamp)
        doLast {
            // A Java argument file: one argument per line, in quotes, with \ doubled
            fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val skip = listOf("-Dorg.gradle", "-Djava.security.manager", "-Deditor.checkout", "-Deditor.golden")
            val lines = jvmArgs.get().filter { arg -> skip.none { arg.startsWith(it) } } + listOf("-cp", classpath.asPath)
            out.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n") { quoted(it) } + "\n")
            java.get().asFile.writeText(launcher.get().executablePath.asFile.path + "\n")
            stamp.get().asFile.writeText("${System.currentTimeMillis()}\n")
        }
    }
}
