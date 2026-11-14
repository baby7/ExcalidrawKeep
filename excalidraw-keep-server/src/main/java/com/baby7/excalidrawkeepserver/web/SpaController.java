package com.baby7.excalidrawkeepserver.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端是单页应用：/ 和 /d/{id} 都交给 index.html 处理，
 * 静态资源仍然由 spring.web.resources.static-locations 提供。
 * （开发期由 vite dev server 顶在前面，这两条路由只在打 fat jar 时生效。）
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/d/{id}"})
    public String index() {
        return "forward:/index.html";
    }
}
