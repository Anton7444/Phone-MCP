package app.phonemcp

import app.phonemcp.policy.ApprovalText
import app.phonemcp.policy.CommandClassifier
import app.phonemcp.policy.PathPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bypasses found in the security review; each used to slip through. */
class SecurityRegressionTest {
    private val protectedRoots = PathPolicy.DEFAULT_DENY.flatMap { PathPolicy.aliases(it) }
    private fun asks(c: String) = CommandClassifier.needsApproval(c, protectedRoots)

    @Test fun rewrittenDestructiveCommandsNowAsk() {
        listOf(
            "/system/bin/rm -rf /sdcard/x", "sh -c 'rm -rf /sdcard/x'", "\"rm\" -rf /sdcard/x", "r''m -rf /sdcard/x",
            "\\rm -rf /sdcard/x", "x=rm; \$x -rf /sdcard/x", "/system/bin/reboot", "/system/bin/setprop a b",
            "echo x > /system/build.prop", "mv a /dev/null", "content query --uri content://sms/inbox",
            "input tap 100 100", "uiautomator dump /sdcard/u.xml", "screencap -p /sdcard/s.png", "mkdir /sdcard/x", "cp a b"
        ).forEach { assertTrue("must ask: $it", asks(it)) }
    }

    @Test fun protectedFoldersAskEvenForReads() {
        listOf("cat /data/data/com.x/databases/a.db", "ls /data/adb", "cat /data/system/locksettings.db",
            "grep -r token /sdcard/Android/data/com.x", "cat /storage/emulated/0/Android/data/com.x/f")
            .forEach { assertTrue("must ask: $it", asks(it)) }
        assertFalse("a similarly named folder is not protected", asks("ls /data/datastore"))
    }

    @Test fun plainReadsStillRunFreely() {
        listOf("ls -la /sdcard", "cat /sdcard/a.txt", "getprop ro.product.model", "dumpsys battery", "df -h", "id", "pm list packages")
            .forEach { assertFalse("should not ask: $it", asks(it)) }
    }

    @Test fun onlyExactRedirectsToDevNullCountAsReadOnly() {
        listOf("echo x >/dev/nullfoo", "echo x > /dev/null.sh", "echo x 1>/dev/null2", "echo x >&2foo")
            .forEach { assertFalse("not read-only: $it", CommandClassifier.isReadOnlySafe(it)) }
        listOf("ls /sdcard 2>/dev/null", "ls /sdcard >/dev/null 2>&1", "ls /sdcard 2>/dev/null | head", "ls x 2>/dev/null;ls y")
            .forEach { assertTrue("read-only: $it", CommandClassifier.isReadOnlySafe(it)) }
    }

    @Test fun readOnlyListNoLongerHasEscapeHatches() {
        listOf(
            "env settings put global x 1", "env svc wifi disable", "env cp a b", "env sh -c 'touch x'",
            "ip netns exec x sh", "dumpsys", "dumpsys notification --noredact", "dumpsys window"
        ).forEach { assertFalse("not read-only: $it", CommandClassifier.isReadOnlySafe(it)) }
        assertTrue(CommandClassifier.isReadOnlySafe("ip addr show"))
        assertTrue(CommandClassifier.isReadOnlySafe("dumpsys battery"))
    }

    @Test fun criticalListCoversTheGaps() {
        listOf(
            "rm -fr /data", "rm -r -f /system", "rm -rf /*", "rm --recursive --force /vendor", "/system/bin/rm -rf /persist",
            "cat img > /dev/block/by-name/boot", "dd if=a of=/dev/block/by-name/boot", "blkdiscard /dev/block/sda",
            "flash_erase /dev/mtd0 0 0", "fastboot -s ABC123 erase boot", "fastboot flash boot x.img",
            "wipefs -a /dev/block/sda1", "cp a /dev/block/mmcblk0p5", "tee /dev/block/sda < a"
        ).forEach { assertTrue("critical: $it", CommandClassifier.isCritical(it)) }
        listOf("rm -rf /sdcard/DCIM", "dd if=/dev/zero of=/sdcard/z bs=1M count=1", "fastboot devices", "cat /dev/block/by-name/boot")
            .forEach { assertFalse("not critical: $it", CommandClassifier.isCritical(it)) }
    }

    @Test fun approvalTextExposesHiddenParts() {
        val evil = "ls " + " ".repeat(700) + "\n\n\n\n; rm -rf /sdcard/x"
        val shown = ApprovalText.display(evil)
        assertTrue(shown.contains("rm -rf /sdcard/x"))
        assertTrue(shown.length < 80)
        val bidi = "echo safe \u202E; rm -rf x"
        assertTrue(ApprovalText.hasHidden(bidi))
        assertTrue(ApprovalText.display(bidi).contains("U+202E"))
        assertFalse(ApprovalText.hasHidden("echo a\n\tb"))
        val long = "a".repeat(120_000)
        assertTrue(ApprovalText.display(long).contains("more characters"))
    }

    @Test fun storageAliasesCoverEveryName() {
        val a = PathPolicy.aliases("/sdcard/Android/data")
        assertTrue("/storage/emulated/0/Android/data" in a && "/sdcard/Android/data" in a && "/data/media/0/Android/data" in a)
        assertEquals(listOf("/data/data"), PathPolicy.aliases("/data/data/"))
    }

    @Test fun readOnlyListRejectsMutatingArguments() {
        listOf(
            "sort -o/data/local/tmp/x /sdcard/in.txt", "sort -ro x", "uniq /sdcard/in.txt /data/local/tmp/x", "logcat -dc",
            "logcat -f/data/local/tmp/x", "logcat -d -f /x", "ifconfig wlan0 down", "hostname x", "ip link s wlan0 down",
            "ip route append 0.0.0.0/1 via 1.2.3.4", "ip addr fl dev wlan0", "ip -b addr", "cd /data; cat system/locksettings.db",
            "/sdcard/ls /data", "ss -K"
        ).forEach { assertFalse("not read-only: $it", CommandClassifier.isReadOnlySafe(it)) }
        listOf("sort a.txt", "uniq a.txt", "logcat -d -t 50", "ip addr show", "ip -4 route show", "hostname", "/system/bin/ls /sdcard")
            .forEach { assertTrue("read-only: $it", CommandClassifier.isReadOnlySafe(it)) }
    }

    @Test fun pathSpellingsCannotHideProtectedFolders() {
        listOf(
            "cat /d''ata/adb/magisk.db", "cat /data/local/tmp/../../adb/magisk.db", "cat /data//adb/x", "cat /data/./adb/x",
            "cat /DATA/ADB/x", "cat /da*/adb/x", "cat /data/system_ce/0/accounts_ce.db", "cat /sdcard/ANDROID/DATA/com.x/f"
        ).forEach { assertTrue("must ask: $it", asks(it)) }
        assertFalse(asks("ls /sdcard/Download/*.jpg"))
    }

    @Test fun quotedCriticalCommandsStayCritical() {
        listOf("r''m -rf /data", "rm -rf /s''ystem", "rm -rf /sy\\stem", "dd if=/dev/zero of=/dev/b''lock/sda")
            .forEach { assertTrue("critical: $it", CommandClassifier.isCritical(it)) }
    }

    @Test fun newDenyRootsAreMergedForExistingInstalls() {
        assertTrue(PathPolicy.ADDED_DENY.all { it in PathPolicy.DEFAULT_DENY })
    }
}
