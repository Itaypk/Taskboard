package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** The IANA zone the browser reports (`Intl.DateTimeFormat().resolvedOptions().timeZone`). */
data class DetectedTimeZoneRequest(@field:NotBlank @field:Size(max = 64) val timeZone: String)
