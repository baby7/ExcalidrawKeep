package com.baby7.excalidrawkeepserver.web;

import com.baby7.excalidrawkeepserver.repo.DrawingFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DrawingApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private DrawingFileRepository drawingFiles;

    @Test
    void createSaveAndRejectStaleVersion() throws Exception {
        String id = create();

        mvc.perform(get("/api/drawings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.elements").isEmpty())
                .andExpect(jsonPath("$.appState.viewBackgroundColor").doesNotExist());

        mvc.perform(put("/api/drawings/" + id + "/scene").contentType(JSON).content(scene(0, "e1", "rectangle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.elements[0].type").value("rectangle"))
                .andExpect(jsonPath("$.appState.viewBackgroundColor").value("#ffffff"));

        // 拿旧版本再存一次：必须是 409，且把库里当前版本带回来
        mvc.perform(put("/api/drawings/" + id + "/scene").contentType(JSON).content(scene(0, "e1", "rectangle")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(1));

        mvc.perform(get("/api/drawings/" + id))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.elements[0].id").value("e1"));
    }

    @Test
    void attachmentUploadIsIdempotentAndInlinedOnLoad() throws Exception {
        String id = create();
        String fileId = "0123456789abcdef0123456789abcdef01234567";
        byte[] content = new byte[]{1, 2, 3, 4, 5};

        mvc.perform(put("/api/drawings/" + id + "/files/" + fileId)
                        .contentType(MediaType.IMAGE_PNG).content(content))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/drawings/" + id + "/files/" + fileId)
                        .contentType(MediaType.IMAGE_PNG).content(content))
                .andExpect(status().isNoContent());

        assertThat(drawingFiles.findAllByDrawingId(id)).hasSize(1);

        mvc.perform(get("/api/drawings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files['" + fileId + "']", startsWith("data:image/png;base64,")));
    }

    @Test
    void savesKeepOnlyLatestContentAndDropHistoryEndpoints() throws Exception {
        String id = create();
        for (int i = 1; i <= 5; i++) {
            mvc.perform(put("/api/drawings/" + id + "/scene").contentType(JSON).content(scene(i - 1, "e" + i, "rectangle")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(i));
        }

        // 只保留最新内容：版本号随着每次保存递增，但场景里只有最后一次写入的元素
        mvc.perform(get("/api/drawings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.elements", hasSize(1)))
                .andExpect(jsonPath("$.elements[0].id").value("e5"));

        // 历史版本接口已经移除
        mvc.perform(get("/api/drawings/" + id + "/snapshots"))
                .andExpect(status().isNotFound());
    }

    @Test
    void renameAndSoftDelete() throws Exception {
        String id = create();

        mvc.perform(patch("/api/drawings/" + id).contentType(JSON).content("{\"name\":\"新名字\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名字"));

        mvc.perform(delete("/api/drawings/" + id))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/drawings/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mvc.perform(get("/api/drawings").param("q", "新名字"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    private String create() throws Exception {
        String body = mvc.perform(post("/api/drawings").contentType(JSON).content("{\"name\":\"测试画布\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private static String scene(int baseVersion, String elementId, String type) {
        return "{\"baseVersion\":" + baseVersion
                + ",\"elements\":[{\"id\":\"" + elementId + "\",\"type\":\"" + type + "\",\"x\":10,\"y\":20}],"
                + "\"appState\":{\"viewBackgroundColor\":\"#ffffff\"}}";
    }
}
