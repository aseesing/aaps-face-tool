// The AAPS watch face tool: converts the design zips of the Custom watch face into Watch Face Format
// (WFF) face APKs, and checks them against our own engine (the parity suite).
//
// This build uses the AAPS source code as an included build (a composite build). The converter draws
// and reads the designs with the real watch face code of AAPS (`:wear:cwfrender`). AAPS never depends
// on this repo.
//
// Where AAPS lies is `aapsDir`: set it in `local.properties` (your own machine) or in
// `gradle.properties` (the default for everybody), or give it with `-PaapsDir=...`.
// `aaps-commit.txt` names the AAPS commit this repo was last tested with; the build warns when the
// checkout is on another commit.

import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

val localProperties = Properties().apply {
    val file = file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** A setting: `-P<name>`, else `local.properties`, else `gradle.properties`. */
fun setting(name: String): String? =
    startParameter.projectProperties[name] ?: localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull

/** The folder of the AAPS checkout. */
val aapsDir: File = run {
    val path = setting("aapsDir") ?: error("Set aapsDir (the folder of the AAPS checkout) in local.properties or gradle.properties")
    file(path).canonicalFile.also {
        require(File(it, "wear/cwfrender/build.gradle.kts").exists()) { "aapsDir $it is not an AAPS checkout with wear/cwfrender" }
        require(File(it, "wear/src/main/kotlin/app/aaps/wear/watchfaces/wff/WffPlanner.kt").exists()) { "aapsDir $it has no WFF planner: use a branch with the WFF complications (local/wear-wff-slim)" }
    }
}
gradle.extra["aapsDir"] = aapsDir

// The designs repository (aaps-watchfaces), for the WFF faces of its zips. Optional.
setting("designsDir")?.let { gradle.extra["designsDir"] = file(it).canonicalFile }

// Warn when the AAPS checkout is not on the commit this repo was tested with
run {
    val tested = file("aaps-commit.txt").takeIf { it.exists() }?.readLines()?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
    val head = runCatching {
        ProcessBuilder("git", "-C", aapsDir.path, "rev-parse", "HEAD").redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
    }.getOrNull()
    if (tested != null && head != null && !head.startsWith(tested)) {
        logger.warn("w: AAPS at $aapsDir is on $head, this repo was tested with $tested (aaps-commit.txt)")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    // The same library versions as AAPS
    versionCatalogs {
        create("libs") {
            from(files(File(aapsDir, "gradle/libs.versions.toml")))
        }
    }
}

rootProject.name = "aaps-face-tool"

// The AAPS projects this build uses. Gradle builds only these (and what they need) from AAPS.
// Gradle still configures every project of an included build (configure on demand works only for
// the main build), so AAPS leaves out its `:app` when another build includes it: see its settings.gradle.
includeBuild(aapsDir) {
    dependencySubstitution {
        substitute(module("aaps:cwfrender")).using(project(":wear:cwfrender"))
        substitute(module("aaps:shared-impl")).using(project(":shared:impl"))
        substitute(module("aaps:core-interfaces")).using(project(":core:interfaces"))
        substitute(module("aaps:core-data")).using(project(":core:data"))
    }
}
