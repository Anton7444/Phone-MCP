package app.phonemcp.tunnel

import java.net.Inet4Address
import java.net.NetworkInterface

/** Local addresses for the LAN-only and Tailscale connection types. */
object NetworkAddresses {
    private fun ipv4s(): List<Pair<String, Inet4Address>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
    } catch (_: Exception) {
        emptyList()
    }

    /** Tailscale's CGNAT range 100.64.0.0/10. */
    fun tailscale(): String? = ipv4s().firstOrNull { (_, a) ->
        val b = a.address
        (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
    }?.second?.hostAddress

    fun lan(): String? {
        val all = ipv4s().filter { (_, a) -> a.isSiteLocalAddress }
        val preferred = all.firstOrNull { (n, _) -> n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap") }
        return (preferred ?: all.firstOrNull())?.second?.hostAddress
    }
}
