// The model of the face editor: the project folder, the JSON form, edits, history, diff and the
// command table. Plain Kotlin for the JVM: no Android and no AAPS code. See docs/face-editor-design.md.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(platform(libs.kotlinx.serialization.bom))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.junit)
    testImplementation(libs.com.google.truth)
}

// The design zips of aaps-watchfaces, for the round trip test. The test is skipped without them.
val designsDir = if (gradle.extra.has("designsDir")) gradle.extra["designsDir"] as File else null

tasks.test {
    useJUnit()
    designsDir?.let { systemProperty("wff.zips", File(it, "cwf-designs").path) }
}
