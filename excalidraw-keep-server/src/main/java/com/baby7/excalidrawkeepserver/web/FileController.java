package com.baby7.excalidrawkeepserver.web;

import com.baby7.excalidrawkeepserver.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 附件上传：请求体就是原始字节，fileId 用 Excalidraw 根据内容算出的哈希，
 * 因此重复 PUT 同一个 fileId 不会产生第二份数据。
 * 场景 JSON 里只留 fileId 引用，保存请求因此从 MB 级降到 KB 级。
 */
@RestController
@RequestMapping("/api/drawings/{drawingId}/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService files;

    @PutMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void put(@PathVariable("drawingId") String drawingId,
                    @PathVariable("fileId") String fileId,
                    @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                    @RequestBody byte[] content) {
        files.put(drawingId, fileId, contentType, content);
    }
}
