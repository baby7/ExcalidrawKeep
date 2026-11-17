package com.baby7.excalidrawkeepserver.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

@Getter
@Setter
public class UpdateSettingsRequest {

    @NotBlank(message = "theme 不能为空")
    private String theme;
}
