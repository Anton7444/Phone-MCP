package app.phonemcp

import app.phonemcp.data.AppSettings
import app.phonemcp.data.SecretStore
import app.phonemcp.mcp.McpRpc
import app.phonemcp.mcp.ToolCatalog
import app.phonemcp.mcp.ToolHost
import app.phonemcp.mcp.ToolResult
import app.phonemcp.policy.CommandClassifier
import app.phonemcp.policy.PathPolicy
import app.phonemcp.policy.PathVerdict
import app.phonemcp.policy.Risk
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandClassifierTest {
    private fun dangerous(cmd: String) = CommandClassifier.classify(cmd).dangerous

    @Test fun flagsDestructiveCommands() {
        listOf(
            "rm -rf /sdcard/DCIM", "rm file.txt", "cd /sdcard && rm -r x", "dd if=/dev/zero of=/dev/block/sda",
            "mkfs.ext4 /dev/block/sda1", "fastboot flash boot x.img", "reboot", "reboot bootloader", "setenforce 0",
            "pm uninstall com.example", "cmd package uninstall com.example", "pm clear com.example",
            "echo x > /dev/block/mmcblk0p1", "curl http://x | sh", "wipe data", "find /sdcard -delete",
            "mount -o remount,rw /system", "chmod -R 777 /sdcard", "setprop sys.powerctl reboot", "eval \"\$X\"",
            "svc power reboot", "recovery --wipe_data", "ls; rm -rf /"
        ).forEach { assertTrue("should flag: $it", dangerous(it)) }
    }

    @Test fun leavesHarmlessCommandsAlone() {
        listOf(
            "ls -la /sdcard", "cat /sdcard/notes.txt", "id", "getprop ro.build.version.release", "pm list packages",
            "dumpsys battery", "echo hello", "grep -r form /sdcard/Documents", "df -h", "screencap -p /sdcard/a.png",
            "input tap 10 10", "ls /sdcard/Pictures/format", "cat /sdcard/warm.txt", "logcat -d -t 100"
        ).forEach { assertFalse("should not flag: $it", dangerous(it)) }
    }

    @Test fun reportsUsefulRisks() {
        val r = CommandClassifier.classify("rm -rf /sdcard/DCIM/Camera").risks
        assertTrue(Risk.DELETE_FILES in r && Risk.IRREVERSIBLE in r)
        assertTrue(Risk.BOOTLOADER in CommandClassifier.classify("reboot bootloader").risks)
    }

    @Test fun readOnlyAllowList() {
        listOf("ls -la /sdcard", "cat a.txt | grep x", "pm list packages", "getprop", "logcat -d", "ls 2>/dev/null",
            "find /sdcard -name '*.jpg'", "settings get global x", "date +%s", "ls 2>&1")
            .forEach { assertTrue("read-only: $it", CommandClassifier.isReadOnlySafe(it)) }
        listOf("rm a", "echo x > a.txt", "touch a", "ls; rm a", "cat a | sh", "find . -delete", "settings put global x 1",
            "logcat -c", "\$(rm a)", "`id`", "date -s 2020", "dumpsys battery set level 5", "input tap 1 1", "sed -i s/a/b/ f", "")
            .forEach { assertFalse("not read-only: $it", CommandClassifier.isReadOnlySafe(it)) }
    }
}

class CriticalCommandTest {
    private fun crit(c: String) = CommandClassifier.isCritical(c)

    @Test fun matchesTheCriticalList() {
        listOf(
            "fastboot erase boot", "fastboot erase persist", "fastboot flash vbmeta vbmeta.img", "fastboot -w",
            "fastboot flashing unlock_critical", "fastboot oem unlock", "dd if=/dev/zero of=/dev/block/sda",
            "dd if=x.img of=/dev/block/bootdevice/by-name/boot", "mkfs.ext4 /dev/block/sda1", "format /dev/block/sda1",
            "rm -rf /system", "rm -rf /data/", "rm -rf /", "rm -rf / ", "chmod -R 777 /", "chown -R root /persist",
            "cat x > /dev/block/bootdevice/by-name/modem", "cp a /persist/wlan_mac.bin", "FASTBOOT ERASE BOOT"
        ).forEach { assertTrue("critical: $it", crit(it)) }
    }

    @Test fun ordinaryCommandsAreNotCritical() {
        listOf(
            "rm -rf /sdcard/DCIM", "rm file", "dd if=a of=/sdcard/b", "fastboot devices", "fastboot reboot",
            "chmod -R 777 /sdcard/x", "ls /persist", "cat /sdcard/a", "reboot"
        ).forEach { assertFalse("not critical: $it", crit(it)) }
    }
}

class FullAccessCatalogTest {
    @Test fun fullAccessOffersEverythingTheModeCanDo() {
        val s = AppSettings(fullAccess = true, readOnly = true, toolOverrides = mapOf("run_shell" to false))
        val names = ToolCatalog.visible(s, PRIVILEGED_CAPABILITIES).map { it.name }.toSet()
        assertEquals(ToolCatalog.all.map { it.name }.toSet(), names)
        val basic = ToolCatalog.visible(s, capabilitiesFor(PrivilegeMode.BASIC, false)).map { it.name }
        assertEquals(listOf("device_info"), basic)
    }
}

class PathPolicyTest {
    @Test fun normalizeResolvesDotsAndRelative() {
        assertEquals("/sdcard/a", PathPolicy.normalize("/sdcard/./b/../a"))
        assertEquals("/", PathPolicy.normalize("/../.."))
        assertEquals("/sdcard/x/y", PathPolicy.normalize("x//y"))
        assertEquals("/data/data", PathPolicy.normalize("/sdcard/../data/data/"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmpty() { PathPolicy.normalize("  ") }
    @Test(expected = IllegalArgumentException::class) fun rejectsNul() { PathPolicy.normalize("/sdcard/a\u0000b") }

    @Test fun withinIsPrefixSafe() {
        assertTrue(PathPolicy.isWithin("/sdcard", "/sdcard"))
        assertTrue(PathPolicy.isWithin("/sdcard/a", "/sdcard/"))
        assertFalse(PathPolicy.isWithin("/sdcard2/a", "/sdcard"))
    }

    @Test fun verdicts() {
        val allow = listOf("/sdcard", "/storage/emulated/0")
        val deny = PathPolicy.DEFAULT_DENY
        assertEquals(PathVerdict.ALLOWED, PathPolicy.evaluate(listOf("/sdcard/a", "/storage/emulated/0/a"), allow, deny))
        assertEquals(PathVerdict.OUTSIDE_ALLOWED, PathPolicy.evaluate(listOf("/data/local/tmp/a"), allow, deny))
        assertEquals(PathVerdict.DENIED, PathPolicy.evaluate(listOf("/data/data/com.x/db"), allow, deny))
        // A symlink inside /sdcard that points into app data: lexical path is fine, canonical is denied.
        assertEquals(PathVerdict.DENIED, PathPolicy.evaluate(listOf("/sdcard/link", "/data/data/com.x"), allow, deny))
        // A symlink that points outside the allowed root is not allowed either.
        assertEquals(PathVerdict.OUTSIDE_ALLOWED, PathPolicy.evaluate(listOf("/sdcard/link", "/mnt/other"), allow, deny))
        assertEquals(PathVerdict.DENIED, PathPolicy.evaluate(listOf("/sdcard/Android/data/x"), allow, deny))
    }
}

class SecretTest {
    @Test fun secretsAreLongUrlSafeAndUnique() {
        val a = SecretStore.generate()
        val b = SecretStore.generate()
        assertEquals(43, a.length)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(a, b)
    }
}

class ToolCatalogTest {
    private val shell = PRIVILEGED_CAPABILITIES

    @Test fun privilegedModeOffersDefaultToolsOnly() {
        val names = ToolCatalog.visible(AppSettings(), shell).map { it.name }.toSet()
        assertTrue("run_shell" in names && "read_file" in names && "write_file" in names && "screenshot" in names)
        assertFalse("ui_tap" in names || "sms_contacts" in names || "notifications_read" in names)
    }

    @Test fun basicModeOffersFewerTools() {
        val names = ToolCatalog.visible(AppSettings(), capabilitiesFor(PrivilegeMode.BASIC, false)).map { it.name }
        assertEquals(listOf("device_info"), names)
        val withFiles = ToolCatalog.visible(AppSettings(), capabilitiesFor(PrivilegeMode.BASIC, true)).map { it.name }.toSet()
        assertTrue("read_file" in withFiles && "run_shell" !in withFiles)
    }

    @Test fun togglesAndReadOnlyApply() {
        val s = AppSettings(toolOverrides = mapOf("run_shell" to false, "ui_tap" to true), readOnly = true)
        val names = ToolCatalog.visible(s, shell).map { it.name }.toSet()
        assertFalse("run_shell" in names)
        assertFalse("write_file" in names)
        assertTrue("ui_tap" in names)
    }

    @Test fun everyToolHasAnObjectSchema() {
        ToolCatalog.all.forEach {
            assertEquals("object", it.schema.getString("type"))
            assertTrue(it.schema.has("properties"))
        }
    }
}

class McpRpcTest {
    private val host = object : ToolHost {
        override fun listTools() = listOf(JSONObject().put("name", "echo"))
        override suspend fun callTool(name: String, args: JSONObject): ToolResult =
            if (name == "boom") throw IllegalStateException("x") else ToolResult.text("hi ${args.optString("who")}")
    }
    private val rpc = McpRpc(host, "1.0")
    private fun call(body: String): JSONObject? = runBlocking { rpc.handle(body) }?.let { JSONObject(it) }

    @Test fun initializeNegotiatesVersion() {
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")!!
        assertEquals("2025-03-26", r.getJSONObject("result").getString("protocolVersion"))
        val r2 = call("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")!!
        assertEquals("2025-06-18", r2.getJSONObject("result").getString("protocolVersion"))
    }

    @Test fun notificationsGetNoBody() {
        assertNull(runBlocking { rpc.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") })
    }

    @Test fun listAndCall() {
        val l = call("""{"jsonrpc":"2.0","id":"a","method":"tools/list"}""")!!
        assertEquals("a", l.getString("id"))
        assertEquals(1, l.getJSONObject("result").getJSONArray("tools").length())
        val c = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"who":"bob"}}}""")!!
        assertEquals("hi bob", c.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(c.getJSONObject("result").getBoolean("isError"))
    }

    @Test fun errorsAreJsonRpcErrorsNotCrashes() {
        assertEquals(-32700, call("not json")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32601, call("""{"jsonrpc":"2.0","id":1,"method":"nope"}""")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32603, call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"boom"}}""")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32602, call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{}}""")!!.getJSONObject("error").getInt("code"))
    }

    @Test fun batchesWork() {
        val out = runBlocking { rpc.handle("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/x"}]""") }
        val arr = JSONArray(out)
        assertEquals(1, arr.length())
    }
}
