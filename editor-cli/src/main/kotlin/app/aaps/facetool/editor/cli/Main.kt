package app.aaps.facetool.editor.cli

import java.io.File
import java.time.Clock
import kotlin.system.exitProcess

/**
 * The folder of `aaps-face-tool`: the environment variable `FACETOOL_HOME`, else the folder this
 * program is installed in (`<checkout>/editor-cli/build/install/facetool/lib/`).
 */
private fun checkout(): File? {
    System.getenv("FACETOOL_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it).canonicalFile }
    val jar = runCatching { File(Cli::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
    val guess = generateSequence(jar) { it.parentFile }.drop(6).firstOrNull() ?: return null
    return guess.takeIf { File(it, "editor-cli").isDirectory && File(it, "settings.gradle.kts").isFile }?.canonicalFile
}

/** Opens [url] in the browser on macOS. Gives false elsewhere, so the address is printed. */
private fun openBrowser(url: String): Boolean {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return false
    return runCatching { ProcessBuilder("open", url).inheritIO().start().waitFor() == 0 }.getOrDefault(false)
}

fun main(args: Array<String>) {
    val home = File(System.getProperty("user.home"))
    val environment = CliEnvironment(
        cwd = File("").absoluteFile,
        checkout = checkout(),
        cacheRoot = File(home, ".cache/aaps-face-editor"),
        editingRoot = System.getenv("FACETOOL_EDITING")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(home, "Projects/aaps-watchfaces/editing")
    )
    val cli = Cli(environment, System.out, System.err, Clock.systemDefaultZone(), stdin = { System.`in`.readBytes().decodeToString() }, openBrowser = ::openBrowser)
    exitProcess(cli.run(args.toList()))
}
