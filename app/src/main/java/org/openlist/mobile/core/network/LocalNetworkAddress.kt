package org.openlist.mobile.core.network

import java.net.URI

object LocalNetworkAddress {
    fun isLikelyLocal(baseUrl: String): Boolean {
        val host = runCatching {
            val normalized = if (baseUrl.contains("://")) baseUrl else "https://$baseUrl"
            URI(normalized).host?.lowercase()
        }.getOrNull() ?: return false

        if (host == "localhost" || host.endsWith(".local") || '.' !in host && ':' !in host) return true
        parseIpv4(host)?.let { octets ->
            return when {
                octets[0] == 10 -> true
                octets[0] == 127 -> true
                octets[0] == 169 && octets[1] == 254 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 100 && octets[1] in 64..127 -> true
                else -> false
            }
        }
        val compactIpv6 = host.removePrefix("[").removeSuffix("]")
        return compactIpv6 == "::1" ||
            compactIpv6.startsWith("fc") ||
            compactIpv6.startsWith("fd") ||
            compactIpv6.matches(Regex("^fe[89ab].*"))
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return octets.toIntArray()
    }
}

