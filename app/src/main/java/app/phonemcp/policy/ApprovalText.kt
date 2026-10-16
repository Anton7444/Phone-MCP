package app.phonemcp.policy

/**
 * What the owner sees on the approval sheet. The real command is never altered; only the display is, so that
 * invisible characters and giant whitespace runs cannot hide the dangerous part of a command.
 */
object ApprovalText {
    private val HIDDEN = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}&&[^\\n\\t]]")
    private const val MAX_DISPLAY = 100_000

    fun hasHidden(cmd: String): Boolean = HIDDEN.containsMatchIn(cmd)

    fun display(cmd: String): String {
        var s = HIDDEN.replace(cmd) { "\u27E6U+%04X\u27E7".format(it.value[0].code) }
        s = s.replace(Regex("[ \\t]{8,}"), " \u22EF ")
        s = s.replace(Regex("\\n{3,}"), "\n\u22EF\n")
        if (s.length > MAX_DISPLAY) s = s.take(MAX_DISPLAY) + "\n[+" + (s.length - MAX_DISPLAY) + " more characters]"
        return s
    }
}
