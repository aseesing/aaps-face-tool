// The command line of the face editor: `facetool`. It runs the local commands with :editor-model
// and sends the other commands to the render server over HTTP. See docs/face-editor-design.md.

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "facetool"
    mainClass.set("app.aaps.facetool.editor.cli.MainKt")
}

dependencies {
    implementation(project(":editor-model"))
    testImplementation(libs.junit.junit)
    testImplementation(libs.com.google.truth)
}

val designsDir = if (gradle.extra.has("designsDir")) gradle.extra["designsDir"] as File else null

tasks.test {
    useJUnit()
    designsDir?.let { systemProperty("wff.zips", File(it, "cwf-designs").path) }
}
