package com.baby7.excalidrawkeepserver.repo;

import com.baby7.excalidrawkeepserver.domain.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
