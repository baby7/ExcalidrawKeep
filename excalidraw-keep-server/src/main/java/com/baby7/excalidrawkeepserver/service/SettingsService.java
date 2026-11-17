package com.baby7.excalidrawkeepserver.service;

import com.baby7.excalidrawkeepserver.domain.AppSetting;
import com.baby7.excalidrawkeepserver.dto.SettingsResponse;
import com.baby7.excalidrawkeepserver.repo.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 全局设置。主题是"用的人"的偏好，不是某张画布的属性，
 * 所以单独一张表存，不放进 drawing。
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    public static final String THEME_KEY = "theme";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private final AppSettingRepository settings;

    @Transactional(readOnly = true)
    public SettingsResponse get() {
        return new SettingsResponse(getTheme());
    }

    @Transactional(readOnly = true)
    public String getTheme() {
        return settings.findById(THEME_KEY)
                .map(AppSetting::getSettingValue)
                .filter(SettingsService::isSupportedTheme)
                .orElse(THEME_LIGHT);
    }

    /** 只接受 light / dark；其他值当坏请求（400），免得库里出现回读不出来的脏值。 */
    @Transactional
    public String updateTheme(String theme) {
        String value = theme == null ? "" : theme.trim().toLowerCase(Locale.ROOT);
        if (!isSupportedTheme(value)) {
            throw new IllegalArgumentException("theme 只能是 light 或 dark");
        }
        AppSetting setting = settings.findById(THEME_KEY)
                .orElseGet(() -> new AppSetting(THEME_KEY, value));
        setting.setSettingValue(value);
        settings.save(setting);
        return value;
    }

    private static boolean isSupportedTheme(String value) {
        return THEME_LIGHT.equals(value) || THEME_DARK.equals(value);
    }
}
