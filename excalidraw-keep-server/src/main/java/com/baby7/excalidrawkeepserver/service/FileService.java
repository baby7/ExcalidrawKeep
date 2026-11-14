package com.baby7.excalidrawkeepserver.service;

import com.baby7.excalidrawkeepserver.config.AppProperties;
import com.baby7.excalidrawkeepserver.domain.DrawingFile;
import com.baby7.excalidrawkeepserver.error.NotFoundException;
import com.baby7.excalidrawkeepserver.error.PayloadTooLargeException;
import com.baby7.excalidrawkeepserver.repo.DrawingFileRepository;
import com.baby7.excalidrawkeepserver.repo.DrawingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 画布图片附件。fileId 由 Excalidraw 按内容算出（SHA-1 前 40 位 hex），
 * 于是 PUT 天然幂等，同一张图重复上传只占一份空间。
 */
@Service
@RequiredArgsConstructor
public class FileService {

    private static final Pattern FILE_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    private final DrawingFileRepository files;
    private final DrawingRepository drawings;
    private final AppProperties props;

    @Transactional
    public boolean put(String drawingId, String fileId, String mimeType, byte[] content) {
        requireDrawing(drawingId);
        if (fileId == null || !FILE_ID.matcher(fileId).matches()) {
            throw new IllegalArgumentException("fileId 不合法");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (content.length > props.getMaxFileBytes()) {
            throw new PayloadTooLargeException("附件超过 " + props.getMaxFileBytes() + " 字节上限");
        }
        DrawingFile.DrawingFileId key = new DrawingFile.DrawingFileId(drawingId, fileId);
        if (files.existsById(key)) {
            // 内容寻址：同 key 必然同内容，直接当幂等成功。
            return false;
        }
        DrawingFile entity = new DrawingFile();
        entity.setId(key);
        entity.setMimeType(normalizeMime(mimeType));
        entity.setSizeBytes(content.length);
        entity.setContent(content);
        entity.setStorage("db");
        files.save(entity);
        return true;
    }

    @Transactional(readOnly = true)
    public Map<String, String> dataUrls(String drawingId) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (DrawingFile file : files.findAllByDrawingId(drawingId)) {
            if (!"db".equals(file.getStorage()) || file.getContent() == null) {
                continue;
            }
            result.put(file.getId().getFileId(), toDataUrl(file.getMimeType(), file.getContent()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, String> mimeTypes(String drawingId) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (DrawingFile file : files.findAllByDrawingId(drawingId)) {
            result.put(file.getId().getFileId(), file.getMimeType());
        }
        return result;
    }

    @Transactional
    public int deleteByDrawing(String drawingId) {
        return files.deleteByDrawingId(drawingId);
    }

    private void requireDrawing(String drawingId) {
        if (!drawings.findVersionById(drawingId).isPresent()) {
            throw new NotFoundException("画布不存在: " + drawingId);
        }
    }

    private static String normalizeMime(String mimeType) {
        if (mimeType == null || mimeType.trim().isEmpty()) {
            return "application/octet-stream";
        }
        String mime = mimeType.trim();
        // 浏览器/客户端可能带参数，如 "image/png;charset=UTF-8"；
        // 存库时只留主类型，否则拼出来的 data URL 不是合法的 "data:image/png;base64,"
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        if (mime.isEmpty()) {
            return "application/octet-stream";
        }
        if (mime.length() > 255) {
            return mime.substring(0, 255);
        }
        return mime;
    }

    private static String toDataUrl(String mimeType, byte[] content) {
        return "data:" + normalizeMime(mimeType) + ";base64," + Base64.getEncoder().encodeToString(content);
    }
}
