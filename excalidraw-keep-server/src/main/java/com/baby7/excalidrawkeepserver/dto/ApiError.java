package com.baby7.excalidrawkeepserver.dto;

import lombok.Getter;

/** 统一错误体：{ "code": "...", "message": "...", "currentVersion": 3 } */
@Getter
public class ApiError {

    private final String code;
    private final String message;
    private final Integer currentVersion;

    public ApiError(String code, String message) {
        this(code, message, null);
    }

    public ApiError(String code, String message, Integer currentVersion) {
        this.code = code;
        this.message = message;
        this.currentVersion = currentVersion;
    }
}
