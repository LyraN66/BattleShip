package com.example.battleship

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** Cihazın yerel ağdaki (WiFi/hotspot) IPv4 adresini bulur. */
    fun localIpv4(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            // Önce hotspot/ap arayüzleri, sonra wlan
            val candidates = ArrayList<Pair<String, String>>() // name to ip
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        candidates.add(ni.name.lowercase() to addr.hostAddress.orEmpty())
                    }
                }
            }
            // Hotspot genelde "ap", "swlan", "rndis"; istemci "wlan"
            candidates.sortBy {
                when {
                    it.first.contains("ap") || it.first.contains("swlan") || it.first.contains("rndis") -> 0
                    it.first.contains("wlan") -> 1
                    else -> 2
                }
            }
            return candidates.firstOrNull()?.second
        } catch (e: Exception) {
            return null
        }
    }
}
