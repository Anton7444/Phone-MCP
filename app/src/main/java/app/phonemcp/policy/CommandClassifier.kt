package app.phonemcp.policy

enum class Risk {
    DELETE_FILES, IRREVERSIBLE, DISK_WRITE, FORMAT, REBOOT, BOOTLOADER, SELINUX,
    UNINSTALL, WIPE, REMOTE_EXEC, SYSTEM_WRITE, BROAD_PERMS, OUTSIDE_ALLOWED_PATH, CRITICAL, UNVERIFIED, PROTECTED_PATH, HIDDEN_CHARS
}

data class Classification(val risks: Set<Risk>) {
    val dangerous: Boolean get() = risks.isNotEmpty()
}

/**
 * A speed bump, NOT a sandbox: it catches the obvious destructive commands so the user gets asked.
 * Obfuscated commands can slip through, which is why the on-device approval and path rules exist too.
 */
object CommandClassifier {
    private const val START = """(?:^|[^\w.-])"""
    private const val END = """(?=\s|$|[;&|)`])"""

    private fun r(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val rules: List<Pair<Regex, Set<Risk>>> = listOf(
        r("""${START}(?:rm|unlink|shred|truncate|rmdir)$END""") to setOf(Risk.DELETE_FILES, Risk.IRREVERSIBLE),
        r("""\bfind\b[^;&|]{0,500}\s-(?:delete|exec(?:dir)?\s+rm)\b""") to setOf(Risk.DELETE_FILES, Risk.IRREVERSIBLE),
        r("""${START}dd\s[^;&|]{0,500}\bof=""") to setOf(Risk.DISK_WRITE, Risk.IRREVERSIBLE),
        r("""${START}(?:mkfs(?:\.\w+)?|mke2fs|make_ext4fs|newfs_msdos|wipefs|blkdiscard|sgdisk|parted|fdisk|sfdisk)$END""") to
            setOf(Risk.FORMAT, Risk.IRREVERSIBLE),
        r("""--wipe_(?:data|cache)|${START}wipe$END|\bfactory[_ -]?reset\b|\bMASTER_CLEAR\b""") to
            setOf(Risk.WIPE, Risk.IRREVERSIBLE),
        r("""${START}fastboot$END""") to setOf(Risk.BOOTLOADER, Risk.IRREVERSIBLE),
        r("""${START}(?:reboot|poweroff|shutdown|halt)$END|\bsvc\s+power\s+(?:reboot|shutdown)\b|sys\.powerctl""") to
            setOf(Risk.REBOOT),
        r("""${START}reboot\s+(?:-\S+\s+)*(?:bootloader|recovery|download|edl|fastboot|sideload)""") to
            setOf(Risk.BOOTLOADER),
        r("""/dev/(?:block|mmcblk|nvme|sd[a-z]|mapper)""") to setOf(Risk.DISK_WRITE),
        r("""${START}setenforce$END""") to setOf(Risk.SELINUX),
        r("""\b(?:pm|cmd\s+package)\s+(?:uninstall|clear|disable|disable-user|hide|suspend|trim-caches)\b""") to
            setOf(Risk.UNINSTALL),
        r("""${START}(?:umount|mount)$END|\bremount\b""") to setOf(Risk.SYSTEM_WRITE),
        r("""${START}(?:chmod|chown|chcon)\s+(?:-\w*R\w*|--recursive)""") to setOf(Risk.BROAD_PERMS),
        r("""\|\s*(?:sh|bash|ash|su|zsh)(?:\s|$)""") to setOf(Risk.REMOTE_EXEC),
        r("""${START}eval$END""") to setOf(Risk.REMOTE_EXEC),
        r("""${START}(?:setprop|resetprop|magisk|ksud)$END""") to setOf(Risk.SYSTEM_WRITE),
        r(""">>?\s*/(?:sys|proc|dev)/|\btee\b[^;&|]{0,500}\s/(?:sys|proc|dev)/""") to setOf(Risk.SYSTEM_WRITE),
        r(""":\(\)\s*\{""") to setOf(Risk.IRREVERSIBLE),
        r(""">>?\s*/(system|vendor|product|odm|apex|data/(data|system|adb|misc))(/|\s|$)""") to setOf(Risk.SYSTEM_WRITE)
    )

    /**
     * Commands that can brick the phone or destroy IMEI/modem data. These always need TWO separate on-device
     * confirmations, even in Full Access mode and even when on-device approval is switched off.
     */
    private val CRITICAL: List<Regex> = listOf(
        // fastboot: erase/flash critical partitions (also with -s SERIAL or other options in between)
        r("""fastboot\b[^;&|]{0,500}\b(erase|flash)\s+(boot|recovery|system|vendor|persist|modem|efs|vbmeta|dtbo)\b"""),
        r("""fastboot\b[^;&|]{0,500}\s-w\b"""),
        r("""fastboot\b[^;&|]{0,500}\b(flashing\s+unlock_critical|oem\s+unlock)\b"""),
        // raw partition writes
        r("""\bdd\b[^;&|]{0,500}\bof=\S*/dev/(block|mmcblk|nvme|sd[a-z]|mapper)"""),
        r("""\bdd\b[^;&|]{0,500}\bof=\S*by-name/"""),
        r("""(>\s*|\btee\s+(-a\s+)?|\bcp\s+\S+\s+)\S*(/dev/(block|mmcblk|nvme|sd[a-z]|mapper)|by-name/)"""),
        // formatting, discarding, erasing flash
        r("""\bmkfs(\.\w+)?\s"""),
        r("""\b(mke2fs|make_ext4fs|wipefs|blkdiscard|flash_erase|flash_eraseall|erase_image|nandwrite)\b"""),
        r("""\bformat\s+\S*/dev/"""),
        // rm on root or system paths, any flag spelling (-rf, -fr, -r -f, --recursive ...)
        r("""(?<![\w.-])rm\b(\s+-{1,2}[a-z][a-z-]*){0,20}\s+/(\*|system|vendor|product|odm|apex|persist|efs|data|boot)?(/|\*|\s|$)"""),
        // recursive chmod / chown on root paths
        r("""(?<![\w.-])(chmod|chown)\s+(-\w*R\w*|--recursive)\s+\S+\s+/(\*|system|vendor|persist|efs)?\s*$"""),
        // IMEI / modem calibration data
        r("""/persist/(wlan_mac|nvm|modem)""")
    )

    /**
     * The command as the shell will see it after quote/backslash removal, with `//`, `/./` and `/x/../` folded away,
     * so `r''m -rf /s\ystem` and `/data//adb` are judged by what they run, not how they are spelled.
     */
    fun normalizeForMatch(cmd: String): String {
        val noQuotes = cmd.replace("'", "").replace("\"", "").replace("\\", "")
        return Regex("""\S+""").replace(noQuotes) { collapsePath(it.value) }
    }

    private fun collapsePath(tok: String): String {
        if (!tok.contains('/')) return tok
        val lead = tok.takeWhile { it != '/' }
        val rest = tok.substring(lead.length)
        val out = ArrayList<String>()
        for (part in rest.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(part)
        }
        return lead + "/" + out.joinToString("/")
    }

    fun isCritical(cmd: String): Boolean {
        val n = normalizeForMatch(cmd)
        return CRITICAL.any { it.containsMatchIn(cmd) || it.containsMatchIn(n) }
    }

    fun classify(cmd: String): Classification {
        val n = normalizeForMatch(cmd)
        val risks = LinkedHashSet<Risk>()
        for ((rx, rs) in rules) if (rx.containsMatchIn(cmd) || rx.containsMatchIn(n)) risks += rs
        return Classification(risks)
    }

    private val GLOB_OR_VAR = Regex("""[*?\[{$~]""")

    /**
     * True when [cmd] names one of [roots] (or anything below it), judged on the shell-normalised text and ignoring
     * case. A path token whose glob/variable part could expand into a root also counts. Over-asking is fine.
     */
    fun mentionsPath(cmd: String, roots: Collection<String>): Boolean {
        val n = normalizeForMatch(cmd)
        val rs = roots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }
        for (r in rs) {
            if (Regex(Regex.escape(r) + """(?=/|\s|$|['"`;&|)])""", RegexOption.IGNORE_CASE).containsMatchIn(n)) return true
        }
        for (tok in n.split(Regex("""[\s;&|()<>]+"""))) {
            val m = GLOB_OR_VAR.find(tok) ?: continue
            if (!tok.contains('/')) continue
            val prefix = tok.substring(0, m.range.first)
            if (prefix.isEmpty() || rs.any { it.startsWith(prefix, ignoreCase = true) || prefix.startsWith(it, ignoreCase = true) }) return true
        }
        return false
    }

    /**
     * Default-ask policy for run_shell outside Full Access: anything that is not provably read-only, that the
     * classifier flags, or that touches a protected folder needs approval on the phone.
     */
    fun needsApproval(cmd: String, protectedRoots: Collection<String>): Boolean =
        classify(cmd).dangerous || !isReadOnlySafe(cmd) || mentionsPath(cmd, protectedRoots)

    // ---- read-only mode: allow-list of provably non-mutating commands -------------------------

    /** Commands with no output-file, exec or mutating options at all: safe whatever their arguments are. */
    private val PLAIN = setOf(
        "ls", "cat", "id", "whoami", "uname", "df", "du", "ps", "getprop", "uptime", "head", "tail",
        "grep", "egrep", "fgrep", "stat", "file", "wc", "which", "printenv", "echo",
        "pwd", "netstat", "free", "cut", "tr", "basename", "dirname", "readlink", "realpath",
        "md5sum", "sha1sum", "sha256sum", "lsof", "getenforce", "test", "true", "false", "seq",
        "nproc", "lsmod", "vmstat", "sleep", "printf", "["
    )
    /** Commands that are read-only only with a vetted argument shape (see [argsReadOnly]). */
    private val VALIDATED = setOf(
        "dumpsys", "find", "pm", "settings", "wm", "ip", "logcat", "top", "date", "sort", "uniq", "service",
        "ifconfig", "hostname", "ss"
    )
    private val READ_ONLY = PLAIN + VALIDATED
    private val SYSTEM_BIN_DIRS = setOf("/system/bin", "/system/xbin", "/bin", "/usr/bin", "/vendor/bin", "/apex/com.android.runtime/bin")
    private val SAFE_DUMPSYS = setOf(
        "battery", "meminfo", "cpuinfo", "diskstats", "wifi", "connectivity", "display", "power", "procstats",
        "netstats", "batterystats", "thermalservice"
    )
    private val SAFE_IP = setOf("addr", "address", "a", "route", "r", "link", "l", "neigh", "neighbor", "n", "rule")
    private val SAFE_IP_VERBS = setOf("show", "list", "ls", "get")
    private val IP_FLAGS = setOf("-4", "-6", "-s", "-o", "-br", "-brief", "-c", "-d", "-details", "-stats")
    private val DUMPSYS_MUTATING = setOf(
        "set", "reset", "unplug", "force-idle", "unforce", "enable", "disable", "step", "whitelist", "write",
        "clear", "reboot", "suspend", "restart", "kill"
    )
    private val FIND_MUTATING = setOf("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
    private val LOGCAT_FLAGS = setOf("-d", "-t", "-T", "-v", "-b", "-s", "-D", "-e")
    private val SAFE_REDIRECT = Regex("""\d?>\s*&\d|\d?>\s*/dev/null""")

    private fun argsReadOnly(name: String, args: List<String>): Boolean = when (name) {
        in PLAIN -> true
        "dumpsys" -> args.isNotEmpty() && (args[0] == "-l" || args[0] in SAFE_DUMPSYS) && args.none { it in DUMPSYS_MUTATING }
        "find" -> args.none { it in FIND_MUTATING }
        "pm" -> args.firstOrNull()?.let { it == "list" || it == "path" || it == "dump" || it.startsWith("get-") } == true
        "settings" -> args.firstOrNull()?.let { it == "get" || it == "list" } == true
        "wm" -> args.size <= 1 && (args.isEmpty() || args[0] == "size" || args[0] == "density")
        "ip" -> {
            // Allow-list, not block-list: iproute2 accepts abbreviated verbs and -b FILE batch mode.
            val rest = args.dropWhile { it in IP_FLAGS }
            rest.isNotEmpty() && rest[0] in SAFE_IP &&
                (rest.size == 1 || rest[1] in SAFE_IP_VERBS) && rest.drop(1).none { it.startsWith("-") }
        }
        // Option VALUES that are not on the list are rejected too, so -f/path, -dc, --file=x all fail.
        "logcat" -> args.all { !it.startsWith("-") || it in LOGCAT_FLAGS }
        "top" -> (args.contains("-n") || args.contains("-b")) && args.none { it.startsWith("--") }
        "date" -> args.isEmpty() || args.all { it.startsWith("+") || it == "-u" }
        "sort" -> args.none { a -> (a.startsWith("--") && a != "--reverse" && a != "--numeric-sort" && a != "--unique") ||
            (a.startsWith("-") && !a.startsWith("--") && a.contains('o')) }
        "uniq" -> args.count { !it.startsWith("-") } <= 1 && args.none { it.startsWith("--") }
        "service" -> args.firstOrNull() == "list"
        "ifconfig", "hostname" -> args.isEmpty()
        "ss" -> args.all { it.matches(Regex("-[tulnpa46]+")) }
        else -> false
    }

    /** True only if every part of the command is on the read-only allow-list. Anything unknown is treated as mutating. */
    fun isReadOnlySafe(cmd: String): Boolean {
        if (cmd.contains('`') || cmd.contains("$(") || cmd.contains("<(") || cmd.contains(">(")) return false
        val stripped = SAFE_REDIRECT.replace(cmd, " ")
        if (stripped.contains('>')) return false
        val segments = stripped.split(';', '&', '|', '\n')
        var any = false
        for (seg in segments) {
            val tokens = seg.replace("\"", "").replace("'", "").trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            any = true
            val name = tokens[0].substringAfterLast('/')
            if (name !in READ_ONLY) return false
            // A path-qualified name must be a system binary, not e.g. /sdcard/ls.
            if (tokens[0].contains('/') && tokens[0].substringBeforeLast('/') !in SYSTEM_BIN_DIRS) return false
            if (!argsReadOnly(name, tokens.drop(1))) return false
        }
        return any
    }
}
