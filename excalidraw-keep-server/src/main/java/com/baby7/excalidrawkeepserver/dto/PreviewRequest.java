package com.baby7.excalidrawkeepserver.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 缩略图单独上报：它由前端空闲时异步生成，不该拖慢场景保存。
 * preview 为 null 表示清除缩略图。
 */
@Getter
@Setter
public class PreviewRequest {

    private String preview;
}
