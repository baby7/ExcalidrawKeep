package com.baby7.excalidrawkeepserver.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/** 列表页只需要这些轻量字段，不查 elements / app_state 这两个大 LOB。 */
@Getter
@AllArgsConstructor
public class DrawingSummary {

    private String id;
    private String name;
    private String preview;
    private Integer version;
    private Instant updatedAt;
}
