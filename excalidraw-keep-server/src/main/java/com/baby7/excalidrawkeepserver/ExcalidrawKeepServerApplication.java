package com.baby7.excalidrawkeepserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExcalidrawKeepServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExcalidrawKeepServerApplication.class, args);
    }

}
