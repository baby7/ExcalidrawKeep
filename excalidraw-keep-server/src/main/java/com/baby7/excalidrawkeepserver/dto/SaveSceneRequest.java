package com.baby7.excalidrawkeepserver.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

/**
 * 保存场景的请求体。elements / appState 用 JsonNode 接收，服务端只校验类型不解析结构，
 * 存库时再写回紧凑 JSON 文本。
 */
@Getter
@Setter
public class SaveSceneRequest {

    /** 客户端拿到的版本号；与库里不一致即视为冲突，返回 409。 */
    private Integer baseVersion;

    private JsonNode elements;

    private JsonNode appState;
}
