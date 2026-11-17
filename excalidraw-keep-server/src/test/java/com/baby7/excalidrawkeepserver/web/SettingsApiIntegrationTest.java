package com.baby7.excalidrawkeepserver.web;

import com.baby7.excalidrawkeepserver.domain.AppSetting;
import com.baby7.excalidrawkeepserver.repo.AppSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppSettingRepository settings;

    @Test
    void defaultsToLightWhenNothingStored() throws Exception {
        settings.deleteAll();

        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("light"));
    }

    @Test
    void themeIsPersistedAndUnknownValueRejected() throws Exception {
        mvc.perform(put("/api/settings").contentType(JSON).content("{\"theme\":\"dark\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("dark"));

        // 换一次请求再读：确认是写进库里的，不只是内存状态
        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("dark"));

        AppSetting stored = settings.findById("theme")
                .orElseThrow(() -> new IllegalStateException("主题没有落库"));
        assertThat(stored.getSettingValue()).isEqualTo("dark");

        mvc.perform(put("/api/settings").contentType(JSON).content("{\"theme\":\"blue\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 复原，避免影响其他用例
        mvc.perform(put("/api/settings").contentType(JSON).content("{\"theme\":\"light\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("light"));
    }
}
