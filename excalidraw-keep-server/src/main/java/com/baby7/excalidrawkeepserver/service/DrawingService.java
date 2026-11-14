package com.baby7.excalidrawkeepserver.service;

import com.baby7.excalidrawkeepserver.config.AppProperties;
import com.baby7.excalidrawkeepserver.domain.Drawing;
import com.baby7.excalidrawkeepserver.dto.DrawingSummary;
import com.baby7.excalidrawkeepserver.dto.PageResponse;
import com.baby7.excalidrawkeepserver.dto.SaveSceneRequest;
import com.baby7.excalidrawkeepserver.dto.SceneResponse;
import com.baby7.excalidrawkeepserver.error.NotFoundException;
import com.baby7.excalidrawkeepserver.error.PayloadTooLargeException;
import com.baby7.excalidrawkeepserver.error.VersionConflictException;
import com.baby7.excalidrawkeepserver.repo.DrawingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DrawingService {

    private static final String UNTITLED = "未命名画布";
    private static final int MAX_PAGE_SIZE = 100;

    private final DrawingRepository drawings;
    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    @Transactional
    public DrawingSummary create(String name) {
        Drawing drawing = Drawing.empty(trimToNull(name) == null ? UNTITLED : trimToNull(name));
        Drawing saved = drawings.saveAndFlush(drawing);
        return new DrawingSummary(saved.getId(), saved.getName(), saved.getPreview(),
                saved.getVersion(), saved.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<DrawingSummary> list(String query, int page, int size) {
        int pageIndex = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(pageIndex, pageSize);
        String keyword = trimToNull(query);
        Page<DrawingSummary> result = keyword == null
                ? drawings.findSummaries(pageable)
                : drawings.searchSummaries("%" + keyword.toLowerCase(Locale.ROOT) + "%", pageable);
        return new PageResponse<DrawingSummary>(result.getContent(), result.getTotalElements(), pageIndex, pageSize);
    }

    @Transactional(readOnly = true)
    public SceneResponse scene(String id) {
        Drawing drawing = drawings.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("画布不存在: " + id));
        SceneResponse response = new SceneResponse();
        response.setId(drawing.getId());
        response.setName(drawing.getName());
        response.setVersion(drawing.getVersion());
        response.setElements(drawing.getElements());
        response.setAppState(drawing.getAppState());
        response.setCreatedAt(drawing.getCreatedAt());
        response.setUpdatedAt(drawing.getUpdatedAt());
        response.setFiles(fileService.dataUrls(id));
        return response;
    }

    /**
     * 保存场景。成功时返回新版本号与新的 updatedAt，前端据此更新本地基线。
     */
    @Transactional
    public SceneResponse save(String id, SaveSceneRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        if (request.getBaseVersion() == null) {
            throw new IllegalArgumentException("缺少 baseVersion");
        }
        JsonNode elementsNode = request.getElements();
        if (elementsNode == null || !elementsNode.isArray()) {
            throw new IllegalArgumentException("elements 必须是数组");
        }
        JsonNode appStateNode = request.getAppState();
        if (appStateNode != null && !appStateNode.isObject()) {
            throw new IllegalArgumentException("appState 必须是对象");
        }

        DrawingSummary meta = drawings.findMetaById(id)
                .orElseThrow(() -> new NotFoundException("画布不存在: " + id));

        String elements = toJson(elementsNode, "elements");
        String appState = appStateNode == null ? "{}" : toJson(appStateNode, "appState");
        Instant now = Instant.now();
        int updated = drawings.updateScene(id, request.getBaseVersion(), elements, appState, now);
        if (updated == 0) {
            throw new VersionConflictException(currentVersion(id));
        }

        int newVersion = request.getBaseVersion() + 1;

        SceneResponse response = new SceneResponse();
        response.setId(id);
        response.setName(meta.getName());
        response.setVersion(newVersion);
        response.setElements(elements);
        response.setAppState(appState);
        response.setFiles(fileService.dataUrls(id));
        response.setUpdatedAt(now);
        return response;
    }

    @Transactional
    public DrawingSummary rename(String id, String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            throw new IllegalArgumentException("画布名不能为空");
        }
        Instant now = Instant.now();
        if (drawings.rename(id, trimmed, now) == 0) {
            throw new NotFoundException("画布不存在: " + id);
        }
        return requireMeta(id);
    }

    @Transactional
    public void updatePreview(String id, String preview) {
        if (preview != null && preview.length() > props.getMaxSceneChars()) {
            throw new PayloadTooLargeException("缩略图过大");
        }
        if (drawings.updatePreview(id, preview, Instant.now()) == 0) {
            throw new NotFoundException("画布不存在: " + id);
        }
    }

    @Transactional
    public void delete(String id) {
        if (drawings.softDelete(id, Instant.now()) == 0) {
            throw new NotFoundException("画布不存在: " + id);
        }
        fileService.deleteByDrawing(id);
    }

    private DrawingSummary requireMeta(String id) {
        return drawings.findMetaById(id)
                .orElseThrow(() -> new NotFoundException("画布不存在: " + id));
    }

    private int currentVersion(String id) {
        return drawings.findVersionById(id)
                .orElseThrow(() -> new NotFoundException("画布不存在: " + id));
    }

    private String toJson(JsonNode node, String field) {
        String text;
        try {
            text = objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(field + " 不是合法 JSON");
        }
        if (text.length() > props.getMaxSceneChars()) {
            throw new PayloadTooLargeException(field + " 超过 " + props.getMaxSceneChars() + " 字符上限");
        }
        return text;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
