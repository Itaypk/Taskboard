package dev.itayp.tasker.calendar.source

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Whether an address is on the public internet, for fetching URLs users type in (SSRF). Anything
 * that could reach the server itself, its network or a cloud metadata endpoint is not.
 */
object PublicAddresses {

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is Inet4Address -> isPublicV4(address.address)
            is Inet6Address -> isPublicV6(address.address)
            else -> false
        }
    }

    private fun isPublicV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xff
        val o1 = b[1].toInt() and 0xff
        return when {
            o0 == 0 -> false // "this network"
            o0 == 100 && o1 in 64..127 -> false // CGNAT, 100.64.0.0/10
            o0 == 192 && o1 == 0 && (b[2].toInt() and 0xff) == 0 -> false // IETF protocol assignments
            o0 == 198 && (o1 == 18 || o1 == 19) -> false // benchmarking
            o0 >= 240 -> false // reserved, broadcast
            else -> true
        }
    }

    private fun isPublicV6(b: ByteArray): Boolean {
        val first = b[0].toInt() and 0xff
        if (first and 0xfe == 0xfc) return false // unique local, fc00::/7
        if (first == 0xfe && (b[1].toInt() and 0xc0) == 0xc0) return false // deprecated site-local, fec0::/10
        // IPv4-mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::a.b.c.d) carry an IPv4 address: judge that.
        val mapped = (0..9).all { b[it].toInt() == 0 } && b[10].toInt() and 0xff == 0xff && b[11].toInt() and 0xff == 0xff
        val nat64 = (b[0].toInt() and 0xff) == 0x00 && (b[1].toInt() and 0xff) == 0x64 &&
            (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b && (4..11).all { b[it].toInt() == 0 }
        if (mapped || nat64) return isPublic(InetAddress.getByAddress(b.copyOfRange(12, 16)))
        // IPv4-compatible (::a.b.c.d, deprecated) and the rest of ::/96.
        if ((0..11).all { b[it].toInt() == 0 }) return false
        return true
    }
}
