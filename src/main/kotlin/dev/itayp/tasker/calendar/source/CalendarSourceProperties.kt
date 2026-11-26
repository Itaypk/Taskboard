package dev.itayp.tasker.calendar.source

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Reading other calendars over ICS (`tasker.calendar-sources`): the user's own private addresses
 * (GitHub issue #347) and, later, public holiday calendars (#413).
 */
@ConfigurationProperties("tasker.calendar-sources")
data class CalendarSourceProperties(
    /**
     * Lets user-supplied addresses resolve to private, loopback or link-local hosts. Off by
     * default: with it on, anyone who can sign up can make the server fetch from its own network.
     * Only for a self-hosted instance whose users read e.g. a Nextcloud on the same LAN.
     */
    val allowPrivateAddresses: Boolean = false,
)
