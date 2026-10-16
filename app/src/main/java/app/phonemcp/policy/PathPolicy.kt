package app.phonemcp.policy

enum class PathVerdict { ALLOWED, OUTSIDE_ALLOWED, DENIED }

/**
 * Pure path rules. Callers pass both the lexically normalised path and the symlink-resolved path;
 * a path is only ALLOWED when every form of it sits inside an allowed root, and DENIED when any form
 * sits inside a denied root.
 */
object PathPolicy {
    val DEFAULT_ALLOW = listOf("/sdcard")
    val DEFAULT_DENY = listOf(
        "/data/data", "/data/user", "/data/user_de", "/data/misc", "/data/misc_ce", "/data/misc_de",
        "/data/system", "/data/system_ce", "/data/system_de", "/data/adb",
        "/system", "/vendor", "/product", "/odm", "/apex", "/proc", "/sys", "/dev",
        "/sdcard/Android/data", "/sdcard/Android/obb"
    )

    /** Entries added to [DEFAULT_DENY] after first release; merged into saved lists so existing installs get them. */
    val ADDED_DENY = setOf("/data/misc_ce", "/data/misc_de", "/data/system_ce", "/data/system_de")

    /** Resolves `.`/`..`/duplicate slashes lexically. Relative paths are taken relative to [cwd]. */
    fun normalize(raw: String, cwd: String = "/sdcard"): String {
        require(raw.isNotBlank()) { "path is empty" }
        require(!raw.contains('\u0000')) { "path contains NUL" }
        val full = if (raw.startsWith("/")) raw else "$cwd/$raw"
        val out = ArrayList<String>()
        for (part in full.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(part)
            }
        }
        return "/" + out.joinToString("/")
    }

    /** The same folder under its other common names, so rules on /sdcard also cover /storage/emulated/0. */
    fun aliases(root: String): List<String> {
        val r = root.trimEnd('/')
        for (p in listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary", "/data/media/0", "/mnt/sdcard")) {
            if (r == p || r.startsWith("$p/")) {
                val rest = r.removePrefix(p)
                return listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary", "/data/media/0").map { it + rest }
            }
        }
        return listOf(r)
    }

    fun isWithin(path: String, root: String, ignoreCase: Boolean = false): Boolean {
        val r = root.trimEnd('/')
        if (r.isEmpty()) return true
        return path.equals(r, ignoreCase) || path.startsWith("$r/", ignoreCase)
    }

    fun evaluate(
        candidates: Collection<String>,
        allowRoots: Collection<String>,
        denyRoots: Collection<String>
    ): PathVerdict {
        for (c in candidates) for (d in denyRoots) if (isWithin(c, d, ignoreCase = true)) return PathVerdict.DENIED
        val allAllowed = candidates.all { c -> allowRoots.any { isWithin(c, it) } }
        return if (allAllowed) PathVerdict.ALLOWED else PathVerdict.OUTSIDE_ALLOWED
    }
}
