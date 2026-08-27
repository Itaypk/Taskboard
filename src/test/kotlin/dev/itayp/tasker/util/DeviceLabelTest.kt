package dev.itayp.tasker.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DeviceLabelTest {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            // Chrome and Edge both claim "Safari"; Edge also claims "Chrome". Most specific wins.
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36|Chrome on macOS",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36 Edg/128.0|Edge on Windows",
            "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0|Firefox on Linux",
            // iOS agents mention "Mac OS X" too, so the device check has to come first.
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1|Safari on iPhone",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36|Chrome on Android",
        ],
    )
    fun `recognizes common browser and OS combinations`(userAgent: String, expected: String) {
        assertThat(deviceLabel(userAgent)).isEqualTo(expected)
    }

    @Test
    fun `falls back to whichever half is recognisable`() {
        assertThat(deviceLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).isEqualTo("Windows")
        assertThat(deviceLabel("Firefox/130.0")).isEqualTo("Firefox")
    }

    @Test
    fun `returns null rather than leaking an unrecognized agent`() {
        assertThat(deviceLabel("curl/8.5.0")).isNull()
        assertThat(deviceLabel(null)).isNull()
        assertThat(deviceLabel("   ")).isNull()
    }

    @Test
    fun `does not choke on an absurdly long agent`() {
        assertThat(deviceLabel("x".repeat(50_000))).isNull()
    }
}
