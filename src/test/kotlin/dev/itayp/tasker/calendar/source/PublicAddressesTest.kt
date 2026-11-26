package dev.itayp.tasker.calendar.source

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicAddressesTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "127.0.0.1", "127.8.9.10", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "100.127.255.255", "192.0.0.8", "198.18.0.1", "224.0.0.1",
            "240.0.0.1", "255.255.255.255",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "fec0::1", "ff02::1",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a00:1", "::7f00:1",
        ],
    )
    fun `refuses addresses that reach the server or its network`(address: String) {
        assertFalse(PublicAddresses.isPublic(InetAddress.getByName(address)), address)
    }

    @ParameterizedTest
    @ValueSource(strings = ["8.8.8.8", "142.250.75.14", "100.63.255.255", "100.128.0.1", "172.32.0.1", "2a00:1450:4001::200e", "::ffff:8.8.8.8", "64:ff9b::808:808"])
    fun `allows public addresses`(address: String) {
        assertTrue(PublicAddresses.isPublic(InetAddress.getByName(address)), address)
    }
}
