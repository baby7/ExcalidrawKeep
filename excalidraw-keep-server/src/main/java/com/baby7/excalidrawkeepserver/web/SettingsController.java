package com.baby7.excalidrawkeepserver.web;

import com.baby7.excalidrawkeepserver.dto.SettingsResponse;
import com.baby7.excalidrawkeepserver.dto.UpdateSettingsRequest;
import com.baby7.excalidrawkeepserver.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    @GetMapping
    public SettingsResponse get() {
        return settings.get();
    }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody UpdateSettingsRequest request) {
        return new SettingsResponse(settings.updateTheme(request.getTheme()));
    }
}
