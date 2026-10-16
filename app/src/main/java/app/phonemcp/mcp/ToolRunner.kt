package app.phonemcp.mcp

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import app.phonemcp.BuildConfig
import app.phonemcp.Capability
import app.phonemcp.ServerState
import app.phonemcp.ToolException
import app.phonemcp.app
import app.phonemcp.data.AuditLog
import app.phonemcp.data.Decision
import app.phonemcp.data.SecretStore
import app.phonemcp.data.SettingsRepository
import app.phonemcp.exec.Executor
import app.phonemcp.exec.MAX_READ_BYTES
import app.phonemcp.exec.ShellCmd
import app.phonemcp.exec.readFully
import app.phonemcp.policy.ApprovalManager
import app.phonemcp.policy.ApprovalOutcome
import app.phonemcp.policy.ApprovalText
import app.phonemcp.policy.CommandClassifier
import app.phonemcp.policy.PathPolicy
import app.phonemcp.policy.PathVerdict
import app.phonemcp.policy.Risk
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Executes MCP tool calls: toggles, mode capabilities, path rules, approval, audit. */
class ToolRunner(
    private val ctx: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val audit: AuditLog,
    private val approvals: ApprovalManager,
    private val state: ServerState,
    private val executorProvider: () -> Executor?
) : ToolHost {

    private class Trace { var decision = Decision.AUTO }

    private val rootCache = ConcurrentHashMap<String, String>()
    private var rootCacheOwner: Executor? = null
    private val violations = ArrayDeque<Long>()

    override fun listTools(): List<JSONObject> {
        val ex = executorProvider() ?: return emptyList()
        return ToolCatalog.visible(settings.value, ex.capabilities).map { it.toJson() }
    }

    override suspend fun callTool(name: String, args: JSONObject): ToolResult {
        val trace = Trace()
        var status = "ok"
        val result = try {
            execute(name, args, trace)
        } catch (e: ToolException) {
            status = e.code
            if (e.code == "BLOCKED" || e.code == "PATH_NOT_ALLOWED" || e.code == "RATE_LIMITED") trace.decision = Decision.BLOCKED
            ToolResult.error(e.code, e.message ?: e.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "INTERNAL_ERROR"
            ToolResult.error("INTERNAL_ERROR", e.message ?: e.javaClass.simpleName)
        }
        state.update { it.copy(calls = it.calls + 1) }
        val known = ToolCatalog.find(name) != null
        audit.add(if (known) name else "(unknown tool)", scrub(summaryOf(name, args)), redact(args), trace.decision, status)
        return result
    }

    // ---- dispatch ---------------------------------------------------------------------------------

    private suspend fun execute(name: String, args: JSONObject, trace: Trace): ToolResult {
        val def = ToolCatalog.find(name) ?: throw ToolException("UNKNOWN_TOOL", "No such tool: $name")
        val ex = executorProvider() ?: throw ToolException("MODE_NOT_SUPPORTED", "Server is not running")
        val s = settings.value
        if (def.capability !in ex.capabilities) {
            throw ToolException("MODE_NOT_SUPPORTED", "$name is not available in ${ex.mode.name.lowercase()} mode")
        }
        if (!s.fullAccess) {
            if (!ToolCatalog.isEnabled(def, s)) throw ToolException("BLOCKED", "$name is turned off on the phone")
            if (s.readOnly && name in READ_ONLY_BLOCKED) throw ToolException("BLOCKED", "Read-only mode is on")
        }
        if (name == "ui_tap" || name == "ui_type") requireInputAllowed()
        return when (name) {
            "run_shell" -> runShell(ex, args, trace)
            "list_dir" -> ToolResult.text(cap(ex.listDir(guard(ex, args.optString("path", "/sdcard"), false, trace))))
            "file_info" -> ToolResult.text(ex.fileInfo(guard(ex, requireString(args, "path"), false, trace)))
            "read_file" -> readFile(ex, args, trace)
            "write_file" -> writeFile(ex, args, trace)
            "screenshot" -> screenshot(ex)
            "logcat" -> logcat(ex, args)
            "device_info" -> ToolResult.text(deviceInfo(ex).toString(2))
            "ui_dump" -> {
                val f = "/data/local/tmp/.pmcp_ui_${java.util.UUID.randomUUID().toString().take(8)}.xml"
                shellText(ex, "uiautomator dump --compressed $f >/dev/null 2>&1; cat $f; rm -f $f", 60_000)
            }
            "ui_tap" -> {
                val x = requireInt(args, "x", 0, 10000)
                val y = requireInt(args, "y", 0, 10000)
                shellText(ex, "input tap $x $y", 10_000, emptyOk = "tapped ($x, $y)")
            }
            "ui_type" -> uiType(ex, args)
            "notifications_read" -> shellText(
                ex,
                "dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=' | head -n 200",
                30_000
            )
            "sms_contacts" -> smsContacts(ex, args)
            else -> throw ToolException("UNKNOWN_TOOL", "No such tool: $name")
        }
    }

    // ---- run_shell --------------------------------------------------------------------------------

    private suspend fun runShell(ex: Executor, args: JSONObject, trace: Trace): ToolResult {
        val cmd = requireString(args, "cmd")
        if (cmd.length > MAX_CMD_CHARS) {
            throw ToolException("INVALID_ARGUMENT", "cmd is longer than $MAX_CMD_CHARS characters; the full text must fit on the approval sheet")
        }
        val timeoutS = (if (args.has("timeout_s")) args.optInt("timeout_s", 30) else 30).coerceIn(1, 300)
        val s = settings.value
        if (!s.fullAccess && s.readOnly && !CommandClassifier.isReadOnlySafe(cmd)) {
            throw ToolException("BLOCKED", "Read-only mode is on and this command is not on the read-only list")
        }
        if (INPUT_INJECTION.containsMatchIn(cmd)) requireInputAllowed()
        val cls = CommandClassifier.classify(cmd)
        val source = "run_shell · ${ex.mode.name.lowercase().replaceFirstChar { it.uppercase() }}"
        val protectedRoots = s.denyPaths.flatMap { PathPolicy.aliases(it) }
        val touchesProtected = CommandClassifier.mentionsPath(cmd, protectedRoots)
        val risks = LinkedHashSet(cls.risks)
        if (ApprovalText.hasHidden(cmd)) risks += Risk.HIDDEN_CHARS
        if (touchesProtected) risks += Risk.PROTECTED_PATH
        if (!CommandClassifier.isReadOnlySafe(cmd)) risks += Risk.UNVERIFIED
        if (CommandClassifier.isCritical(cmd)) approveCritical(cmd, risks, source, trace)
        // Outside Full Access everything that is not provably read-only asks first (a blacklist alone is bypassable).
        else if (!s.fullAccess && (cls.dangerous || Risk.UNVERIFIED in risks || touchesProtected)) approve(cmd, risks, source, trace)
        val r = ex.exec(cmd, timeoutS * 1000L)
        val out = JSONObject()
            .put("exit_code", r.exit)
            .put("stdout", r.stdout.take(OUTPUT_CAP))
            .put("stderr", r.stderr.take(OUTPUT_CAP))
            .put("truncated", r.truncated || r.stdout.length > OUTPUT_CAP || r.stderr.length > OUTPUT_CAP)
            .put("timed_out", r.timedOut)
        return ToolResult.text(out.toString(2))
    }

    /** Critical commands: two separate confirmations in a row, whatever the settings say. */
    private fun deviceSecure(): Boolean =
        try { ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure } catch (_: Exception) { false }

    /**
     * Nothing may synthesise touches while an approval sheet is waiting (it could press Allow itself) or while
     * Phone MCP's own screens are visible (it could switch on Full Access or widen the path rules).
     */
    private fun requireInputAllowed() {
        if (approvals.pending.value != null) {
            throw ToolException("BLOCKED", "Input injection is paused while an approval is waiting on the phone")
        }
        if (ctx.app.uiVisible) {
            throw ToolException("BLOCKED", "Input injection is refused while the Phone MCP app is on screen")
        }
    }

    private suspend fun approveCritical(command: String, risks: Set<Risk>, source: String, trace: Trace) {
        for (step in 1..2) approve(command, risks + Risk.CRITICAL, source, trace, step, 2, force = true)
    }

    private suspend fun approve(
        command: String, risks: Set<Risk>, source: String, trace: Trace, step: Int = 1, steps: Int = 1, force: Boolean = false
    ) {
        if (!deviceSecure()) {
            // Without a screen lock there is nothing to prove the owner is the one tapping Allow.
            throw ToolException("BLOCKED", "Set a screen lock on the phone before dangerous actions can be approved")
        }
        if (!force && !settings.value.approval) {
            throw ToolException("BLOCKED", "This action needs approval on the phone, and on-device approval is switched off")
        }
        when (approvals.request(command, risks, source, step = step, steps = steps)) {
            ApprovalOutcome.APPROVED -> trace.decision = Decision.APPROVED
            ApprovalOutcome.DENIED -> {
                trace.decision = Decision.DENIED
                throw ToolException("DENIED_BY_USER", "The phone's owner denied this action")
            }
            ApprovalOutcome.TIMEOUT -> {
                trace.decision = Decision.TIMEOUT
                throw ToolException("APPROVAL_TIMEOUT", "No answer on the phone within 60 seconds; treated as denied")
            }
        }
    }

    // ---- files ------------------------------------------------------------------------------------

    private suspend fun guard(ex: Executor, raw: String, write: Boolean, trace: Trace, detail: String = ""): String {
        val lexical = try {
            PathPolicy.normalize(raw)
        } catch (e: IllegalArgumentException) {
            throw ToolException("INVALID_ARGUMENT", e.message ?: "bad path")
        }
        if (settings.value.fullAccess) {
            // No path rules. Raw partition and IMEI/modem paths still need two confirmations when written.
            if (write && CommandClassifier.isCritical("> $lexical")) {
                approveCritical("write_file $lexical$detail", emptySet(), "write_file · ${ex.mode.name.lowercase().replaceFirstChar { it.uppercase() }}", trace)
            }
            return lexical
        }
        val canonical = PathPolicy.normalize(ex.canonicalize(lexical))
        val s = settings.value
        val allow = rootsOf(s.allowPaths, ex)
        val deny = rootsOf(s.denyPaths, ex)
        when (PathPolicy.evaluate(listOf(lexical, canonical), allow, deny)) {
            PathVerdict.ALLOWED -> {}
            PathVerdict.DENIED -> {
                noteViolation()
                throw ToolException("PATH_NOT_ALLOWED", "$lexical is blocked by the path rules")
            }
            PathVerdict.OUTSIDE_ALLOWED -> {
                if (!write) {
                    noteViolation()
                    throw ToolException("PATH_NOT_ALLOWED", "$lexical is outside the allowed folders")
                }
                approve("write_file $canonical$detail", setOf(Risk.OUTSIDE_ALLOWED_PATH), "write_file · ${ex.mode.name.lowercase().replaceFirstChar { it.uppercase() }}", trace)
            }
        }
        return canonical
    }

    private suspend fun rootsOf(list: List<String>, ex: Executor): List<String> {
        if (rootCacheOwner !== ex) {
            rootCache.clear()
            rootCacheOwner = ex
        }
        val out = LinkedHashSet<String>()
        for (raw in list) {
            val n = try { PathPolicy.normalize(raw) } catch (_: IllegalArgumentException) { continue }
            out += n
            out += rootCache.getOrPut(n) { PathPolicy.normalize(ex.canonicalize(n)) }
        }
        return out.toList()
    }

    @Synchronized
    private fun noteViolation() {
        val now = System.currentTimeMillis()
        while (violations.isNotEmpty() && now - violations.first() > 60_000) violations.removeFirst()
        violations.addLast(now)
        if (violations.size > 20) throw ToolException("RATE_LIMITED", "Too many blocked path requests; slow down")
    }

    private suspend fun readFile(ex: Executor, args: JSONObject, trace: Trace): ToolResult {
        val path = guard(ex, requireString(args, "path"), false, trace)
        val offset = if (args.has("offset")) args.optLong("offset", 0) else 0L
        if (offset < 0) throw ToolException("INVALID_ARGUMENT", "offset must be >= 0")
        val length = (if (args.has("length")) args.optLong("length", MAX_READ_BYTES) else MAX_READ_BYTES).coerceIn(1, MAX_READ_BYTES)
        val bytes = ex.readFile(path, offset, length)
        val meta = JSONObject().put("offset", offset).put("bytes_read", bytes.size).put("eof", bytes.size < length)
        val body: String = if (args.optBoolean("base64", false)) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } else {
            if (bytes.any { it.toInt() == 0 }) {
                throw ToolException("INVALID_ARGUMENT", "File looks binary; call again with base64=true")
            }
            String(bytes, Charsets.UTF_8)
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", body))
            .put(JSONObject().put("type", "text").put("text", meta.toString()))
        return ToolResult(content, false)
    }

    private suspend fun writeFile(ex: Executor, args: JSONObject, trace: Trace): ToolResult {
        val rawPath = requireString(args, "path")
        if (!args.has("content") || args.isNull("content")) throw ToolException("INVALID_ARGUMENT", "content is required")
        val data = args.getString("content").toByteArray(Charsets.UTF_8)
        if (data.size > MAX_READ_BYTES) {
            throw ToolException("INVALID_ARGUMENT", "content is larger than 2 MB; write it in parts with append=true")
        }
        val append = args.optBoolean("append", false)
        val path = guard(ex, rawPath, true, trace, " (${data.size} bytes${if (append) ", append" else ""})")
        ex.writeFile(path, data, append)
        return ToolResult.text("wrote ${data.size} bytes to $path")
    }

    // ---- other tools --------------------------------------------------------------------------------

    private suspend fun screenshot(ex: Executor): ToolResult {
        val tmp = "/data/local/tmp/.pmcp_shot_${java.util.UUID.randomUUID().toString().take(8)}.png"
        val r = ex.exec("screencap -p $tmp", 20_000)
        if (r.exit != 0) throw ToolException("EXEC_FAILED", r.stderr.trim().ifEmpty { "screencap failed (exit ${r.exit})" })
        try {
            val png = ex.readFully(tmp, 16L * 1024 * 1024)
            if (png.isEmpty()) throw ToolException("EXEC_FAILED", "screenshot was empty")
            val img = JSONObject().put("type", "image")
                .put("data", java.util.Base64.getEncoder().encodeToString(png))
                .put("mimeType", "image/png")
            return ToolResult(JSONArray().put(img), false)
        } finally {
            try { ex.exec("rm -f $tmp", 5_000) } catch (_: Exception) {}
        }
    }

    private suspend fun logcat(ex: Executor, args: JSONObject): ToolResult {
        val lines = (if (args.has("lines")) args.optInt("lines", 200) else 200).coerceIn(1, 2000)
        val filter = args.optString("filter", "").trim()
        if (filter.length > MAX_FILTER_CHARS) throw ToolException("INVALID_ARGUMENT", "filter is longer than $MAX_FILTER_CHARS characters")
        val spec = Regex("""^(?:[\w.*\-]+:[VDIWEFS*](?:\s+|$))+(?:\*:[VDIWEFS])?$|^\*:[VDIWEFS]$""")
        val cmd = when {
            filter.isEmpty() -> "logcat -d -t $lines"
            spec.matches(filter) -> "logcat -d -t $lines ${filter.split(Regex("\\s+")).joinToString(" ") { ShellCmd.quote(it) }}"
            else -> "logcat -d -t $lines | grep -i -F -- ${ShellCmd.quote(filter)}"
        }
        return shellText(ex, cmd, 30_000)
    }

    private suspend fun uiType(ex: Executor, args: JSONObject): ToolResult {
        val text = requireString(args, "text")
        if (text.length > 500) throw ToolException("INVALID_ARGUMENT", "text is longer than 500 characters")
        if (text.any { it.code !in 32..126 }) throw ToolException("INVALID_ARGUMENT", "only printable ASCII can be typed")
        return shellText(ex, "input text ${ShellCmd.quote(text.replace(" ", "%s"))}", 10_000, emptyOk = "typed ${text.length} characters")
    }

    private suspend fun smsContacts(ex: Executor, args: JSONObject): ToolResult {
        val limit = (if (args.has("limit")) args.optInt("limit", 20) else 20).coerceIn(1, 100)
        val cmd = when (args.optString("kind")) {
            "sms" -> "content query --uri content://sms/inbox --projection address:date:body --sort 'date DESC' | head -n $limit"
            "contacts" -> "content query --uri content://com.android.contacts/data/phones --projection display_name:data1 | head -n $limit"
            else -> throw ToolException("INVALID_ARGUMENT", "kind must be 'sms' or 'contacts'")
        }
        return shellText(ex, cmd, 30_000)
    }

    private suspend fun shellText(ex: Executor, cmd: String, timeoutMs: Long, emptyOk: String? = null): ToolResult {
        val r = ex.exec(cmd, timeoutMs)
        if (r.exit != 0 && r.stdout.isBlank()) {
            throw ToolException("EXEC_FAILED", r.stderr.trim().ifEmpty { "command failed (exit ${r.exit})" })
        }
        val out = r.stdout.ifBlank { emptyOk ?: "(no output)" }
        return ToolResult.text(cap(out))
    }

    @Suppress("DEPRECATION")
    private fun deviceInfo(ex: Executor): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("battery_percent", level)
            .put("charging", charging)
            .put("network", net)
            .put("privilege_mode", ex.mode.name.lowercase())
            .put("app_version", BuildConfig.VERSION_NAME)
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun requireString(a: JSONObject, key: String): String {
        val v = a.opt(key)
        if (v !is String || v.isBlank()) throw ToolException("INVALID_ARGUMENT", "$key is required")
        return v
    }

    private fun requireInt(a: JSONObject, key: String, min: Int, max: Int): Int {
        if (!a.has(key) || a.isNull(key)) throw ToolException("INVALID_ARGUMENT", "$key is required")
        val v = a.optInt(key, Int.MIN_VALUE)
        if (v < min || v > max) throw ToolException("INVALID_ARGUMENT", "$key must be between $min and $max")
        return v
    }

    private fun cap(s: String) = if (s.length > OUTPUT_CAP) s.take(OUTPUT_CAP) + "\n[truncated]" else s

    private fun summaryOf(name: String, a: JSONObject): String = when (name) {
        "run_shell" -> a.optString("cmd")
        "list_dir", "file_info", "read_file", "write_file" -> a.optString("path")
        "logcat" -> a.optString("filter")
        "ui_tap" -> "${a.opt("x")}, ${a.opt("y")}"
        "ui_type" -> "(${a.optString("text").length} chars)"
        "sms_contacts" -> a.optString("kind")
        else -> ""
    }.replace('\n', ' ').take(300)

    private fun scrub(s: String): String {
        val sec = secrets.mcpSecret()
        return s.replace(sec, "<secret>").replace(Regex("""/mcp/[A-Za-z0-9_-]{16,}"""), "/mcp/<secret>")
    }

    /** Args for the audit log: file bodies and typed text are reduced to their size; the secret never appears. */
    private fun redact(a: JSONObject): String {
        val c = JSONObject(a.toString())
        if (c.has("content")) c.put("content", "<${c.optString("content").length} chars>")
        if (c.has("text")) c.put("text", "<${c.optString("text").length} chars>")
        return scrub(c.toString()).take(2000)
    }

    companion object {
        private const val OUTPUT_CAP = 20_000
        private const val MAX_CMD_CHARS = 10_000
        private const val MAX_FILTER_CHARS = 200
        private val READ_ONLY_BLOCKED = setOf("write_file", "ui_tap", "ui_type")
        private val INPUT_INJECTION = Regex("""(?<![\w.-])(input|uiautomator|sendevent|monkey)(?![\w.-])|/dev/input""")
    }
}
