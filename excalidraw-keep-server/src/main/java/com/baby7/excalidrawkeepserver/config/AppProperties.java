package com.baby7.excalidrawkeepserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用级限制参数，全部可以通过环境变量覆盖（见 application.yml 中的 ${...} 占位符）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 单个场景 elements + appState 的最大字符数。 */
    private long maxSceneChars = 32L * 1024 * 1024;

    /** 单个附件（图片）的最大字节数。 */
    private long maxFileBytes = 8L * 1024 * 1024;
}
