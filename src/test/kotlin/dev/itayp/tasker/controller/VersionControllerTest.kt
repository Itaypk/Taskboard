package dev.itayp.tasker.controller

import dev.itayp.tasker.config.AppVersion
import dev.itayp.tasker.config.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(VersionController::class)
@Import(SecurityConfiguration::class, AppVersion::class)
class VersionControllerTest(@Autowired val mockMvc: MockMvc) {

    // Anonymous on purpose: the deploy probes it with no session. "dev" because test runs have no
    // generated build-info.properties.
    @Test
    fun `GET version is public and reports the commit`() {
        mockMvc.perform(get("/api/version"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.commit").value("dev"))
    }
}
