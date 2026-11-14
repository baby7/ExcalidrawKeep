package com.baby7.excalidrawkeepserver.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * 编辑器加载画布用的响应。elements / appState 在库里本来就是 JSON 文本，
 * 用 @JsonRawValue 原样嵌进响应，避免"解析成树再序列化"的两次无谓开销。
 */
@Getter
@Setter
public class SceneResponse {

    private String id;
    private String name;
    private Integer version;
    private String elements;
    private String appState;

    /** fileId → dataURL，加载时由前端回填到 elements 的 image 元素里。 */
    private Map<String, String> files;

    private Instant createdAt;
    private Instant updatedAt;

    @JsonRawValue
    public String getElements() {
        return elements;
    }

    @JsonRawValue
    public String getAppState() {
        return appState;
    }
}
