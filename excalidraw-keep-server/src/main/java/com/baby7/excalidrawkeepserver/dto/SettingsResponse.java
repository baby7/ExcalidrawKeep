package com.baby7.excalidrawkeepserver.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SettingsResponse {

    /** light 或 dark */
    private final String theme;
}
