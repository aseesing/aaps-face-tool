package app.aaps.facetool.editor.model

/** The exit codes of `facetool` (design section 8.1). */
object ExitCodes {

    /** Done. */
    const val OK = 0

    /** Done, but a check has errors. */
    const val CHECK_ERRORS = 1

    /** Wrong use: an unknown command or a bad argument. */
    const val WRONG_USE = 2

    /** The design does not read. */
    const val DESIGN_UNREADABLE = 3

    /** The server failed or did not start. */
    const val SERVER_FAILED = 4
}

/** An error with the exit code the command ends with. The message is for a person. */
class FacetoolException(message: String, val exitCode: Int) : Exception(message)

/** Wrong use (exit code 2). */
fun wrongUse(message: String): Nothing = throw FacetoolException(message, ExitCodes.WRONG_USE)

/** The design does not read (exit code 3). */
fun unreadable(message: String): Nothing = throw FacetoolException(message, ExitCodes.DESIGN_UNREADABLE)
