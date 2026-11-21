package app.aaps.facetool.editor.model

import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Reads files of a folder as they are in a git commit. */
interface GitReader {

    /** The file names under [dir] in the commit [rev], with `/` for sub folders. */
    fun list(dir: File, rev: String): List<String>

    /** The bytes of the file [name] under [dir] in the commit [rev]. */
    fun read(dir: File, rev: String, name: String): ByteArray
}

/** [GitReader] with the `git` program. */
class ProcessGitReader : GitReader {

    override fun list(dir: File, rev: String): List<String> =
        run(dir, "ls-tree", "-r", "--name-only", "-z", rev, "./").decodeToString().split('\u0000').filter { it.isNotEmpty() }

    override fun read(dir: File, rev: String, name: String): ByteArray = run(dir, "show", "$rev:./$name")

    private fun run(dir: File, vararg args: String): ByteArray {
        val errors = File.createTempFile("facetool-git", ".txt")
        try {
            val process = ProcessBuilder(listOf("git", "-C", dir.path) + args).redirectError(errors).start()
            val out = process.inputStream.readBytes()
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) wrongUse("git ${args.first()} failed: ${errors.readText().trim()}")
            return out
        } finally {
            errors.delete()
        }
    }
}

/**
 * The sources of `facetool diff` (design section 8.2): a zip, a folder, `history:<n>` (the project
 * after history entry n; 0 is the design at `open`) or `git:<rev>` (the project folder in a git commit).
 */
class DesignSources(private val clock: Clock, private val git: GitReader) {

    fun load(source: String, project: ProjectFolder?): DesignSnapshot = when {
        source.startsWith("history:") -> {
            val n = source.removePrefix("history:").toIntOrNull() ?: wrongUse("$source: history:<n> needs a number")
            History(project ?: wrongUse("$source needs a project folder"), clock).snapshotAt(n)
        }

        source.startsWith("git:")     -> {
            val dir = (project ?: wrongUse("$source needs a project folder")).dir
            val rev = source.removePrefix("git:")
            val names = git.list(dir, rev).filter { !it.startsWith(ProjectFolder.TOOL_DIR + "/") }
            if (ResourceNames.DESIGN_JSON !in names) wrongUse("$source: the commit has no ${ResourceNames.DESIGN_JSON} in ${dir.path}")
            DesignSnapshot(
                AndroidJson.parseObject(git.read(dir, rev, ResourceNames.DESIGN_JSON).decodeToString(), "${ResourceNames.DESIGN_JSON} of $source"),
                names.filter { it != ResourceNames.DESIGN_JSON }.associateWith { git.read(dir, rev, it) }
            )
        }

        File(source).isDirectory      -> ProjectFolder(File(source)).snapshot()

        File(source).isFile           -> {
            val entries = DesignZip.read(File(source).readBytes())
            val json = entries.firstOrNull { it.name == ResourceNames.DESIGN_JSON } ?: unreadable("$source has no ${ResourceNames.DESIGN_JSON}")
            DesignSnapshot(AndroidJson.parseObject(json.bytes.decodeToString(), "${ResourceNames.DESIGN_JSON} of $source"), entries.filter { it !== json }.associate { it.name to it.bytes })
        }

        else                          -> wrongUse("$source is not a zip, a folder, history:<n> or git:<rev>")
    }
}
