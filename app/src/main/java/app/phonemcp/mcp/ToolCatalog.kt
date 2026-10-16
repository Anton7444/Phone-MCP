package app.phonemcp.mcp

import app.phonemcp.Capability
import app.phonemcp.data.AppSettings
import org.json.JSONArray
import org.json.JSONObject

class ToolDef(
    val name: String,
    val description: String,
    val capability: Capability,
    val schema: JSONObject,
    val sensitive: Boolean = false,
    val defaultEnabled: Boolean = !sensitive,
    val readOnlyHint: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", schema)
        .put("annotations", JSONObject().put("readOnlyHint", readOnlyHint).put("openWorldHint", false))
}

object ToolCatalog {
    private fun prop(type: String, desc: String, default: Any? = null, min: Long? = null, max: Long? = null) =
        JSONObject().put("type", type).put("description", desc).apply {
            if (default != null) put("default", default)
            if (min != null) put("minimum", min)
            if (max != null) put("maximum", max)
        }

    private fun schema(required: List<String>, vararg props: Pair<String, JSONObject>) = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .put("required", JSONArray(required))

    val all: List<ToolDef> = listOf(
        ToolDef(
            "run_shell",
            "Run a shell command on the phone and return exit code, stdout and stderr. Dangerous commands pause until the phone's owner approves them on the device.",
            Capability.SHELL,
            schema(
                listOf("cmd"),
                "cmd" to prop("string", "Shell command to run"),
                "timeout_s" to prop("integer", "Timeout in seconds (default 30, max 300)", 30, 1, 300)
            )
        ),
        ToolDef(
            "list_dir", "List a directory.", Capability.FILES,
            schema(emptyList(), "path" to prop("string", "Directory path (default /sdcard)", "/sdcard")),
            readOnlyHint = true
        ),
        ToolDef(
            "file_info", "Size, permissions and type of a file or directory.", Capability.FILES,
            schema(listOf("path"), "path" to prop("string", "File or directory path")),
            readOnlyHint = true
        ),
        ToolDef(
            "read_file",
            "Read a file (max 2 MB per call). Page through large files with offset/length. Set base64 for binary data.",
            Capability.FILES,
            schema(
                listOf("path"),
                "path" to prop("string", "File path"),
                "offset" to prop("integer", "Byte offset to start at (default 0)", 0, 0),
                "length" to prop("integer", "Bytes to read (default and max 2097152)", 2097152, 1, 2097152),
                "base64" to prop("boolean", "Return the bytes base64-encoded", false)
            ),
            readOnlyHint = true
        ),
        ToolDef(
            "write_file", "Write text to a file. Writes outside the allowed folders need approval on the phone.",
            Capability.FILES,
            schema(
                listOf("path", "content"),
                "path" to prop("string", "File path"),
                "content" to prop("string", "Text to write (max 2 MB)"),
                "append" to prop("boolean", "Append instead of overwrite", false)
            )
        ),
        ToolDef("screenshot", "Take a screenshot of the phone screen (PNG).", Capability.SCREENSHOT, schema(emptyList()), readOnlyHint = true),
        ToolDef(
            "logcat", "Read recent Android logs.", Capability.LOGCAT,
            schema(
                emptyList(),
                "filter" to prop("string", "Logcat filter spec such as '*:E' or 'MyTag:D *:S', or plain text to search for"),
                "lines" to prop("integer", "Number of lines (default 200, max 2000)", 200, 1, 2000)
            ),
            readOnlyHint = true
        ),
        ToolDef("device_info", "Model, Android version, battery and network of the phone.", Capability.DEVICE_INFO, schema(emptyList()), readOnlyHint = true),
        ToolDef("ui_dump", "Dump the current screen's UI hierarchy as XML.", Capability.UI_CONTROL, schema(emptyList()), sensitive = true, readOnlyHint = true),
        ToolDef(
            "ui_tap", "Tap the screen at pixel coordinates.", Capability.UI_CONTROL,
            schema(listOf("x", "y"), "x" to prop("integer", "X pixel", null, 0, 10000), "y" to prop("integer", "Y pixel", null, 0, 10000)),
            sensitive = true
        ),
        ToolDef(
            "ui_type", "Type ASCII text into the focused field.", Capability.UI_CONTROL,
            schema(listOf("text"), "text" to prop("string", "Text to type (ASCII, max 500 chars)")),
            sensitive = true
        ),
        ToolDef("notifications_read", "Read current notifications (titles and text).", Capability.NOTIFICATIONS, schema(emptyList()), sensitive = true, readOnlyHint = true),
        ToolDef(
            "sms_contacts", "Read recent SMS messages or contacts.", Capability.SMS_CONTACTS,
            schema(
                listOf("kind"),
                "kind" to prop("string", "'sms' or 'contacts'"),
                "limit" to prop("integer", "Max rows (default 20, max 100)", 20, 1, 100)
            ),
            sensitive = true, readOnlyHint = true
        )
    )

    fun find(name: String): ToolDef? = all.firstOrNull { it.name == name }

    fun isEnabled(def: ToolDef, s: AppSettings): Boolean = s.fullAccess || (s.toolOverrides[def.name] ?: def.defaultEnabled)

    /** Tools offered right now: backend capability AND user toggle AND read-only mode. */
    fun visible(s: AppSettings, caps: Set<Capability>): List<ToolDef> =
        all.filter { it.capability in caps && isEnabled(it, s) && !(s.readOnly && !s.fullAccess && it.name == "write_file") }
}
