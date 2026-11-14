package com.baby7.excalidrawkeepserver.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Getter
@Setter
public class CreateDrawingRequest {

    @NotBlank
    @Size(max = 255)
    private String name;
}
