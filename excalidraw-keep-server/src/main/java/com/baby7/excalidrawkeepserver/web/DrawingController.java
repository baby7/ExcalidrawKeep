package com.baby7.excalidrawkeepserver.web;

import com.baby7.excalidrawkeepserver.dto.CreateDrawingRequest;
import com.baby7.excalidrawkeepserver.dto.DrawingSummary;
import com.baby7.excalidrawkeepserver.dto.PageResponse;
import com.baby7.excalidrawkeepserver.dto.PreviewRequest;
import com.baby7.excalidrawkeepserver.dto.RenameDrawingRequest;
import com.baby7.excalidrawkeepserver.dto.SaveSceneRequest;
import com.baby7.excalidrawkeepserver.dto.SceneResponse;
import com.baby7.excalidrawkeepserver.service.DrawingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.net.URI;

@RestController
@RequestMapping("/api/drawings")
@RequiredArgsConstructor
public class DrawingController {

    private final DrawingService drawings;

    @GetMapping
    public PageResponse<DrawingSummary> list(@RequestParam(name = "q", required = false) String q,
                                             @RequestParam(name = "page", defaultValue = "0") int page,
                                             @RequestParam(name = "size", defaultValue = "24") int size) {
        return drawings.list(q, page, size);
    }

    @PostMapping
    public ResponseEntity<DrawingSummary> create(@Valid @RequestBody CreateDrawingRequest request) {
        DrawingSummary created = drawings.create(request.getName());
        return ResponseEntity.created(URI.create("/api/drawings/" + created.getId())).body(created);
    }

    @GetMapping("/{id}")
    public SceneResponse scene(@PathVariable("id") String id) {
        return drawings.scene(id);
    }

    /** 保存场景；baseVersion 落后时抛 VersionConflictException，由全局异常处理转成 409。 */
    @PutMapping("/{id}/scene")
    public SceneResponse save(@PathVariable("id") String id, @RequestBody SaveSceneRequest request) {
        return drawings.save(id, request);
    }

    @PatchMapping("/{id}")
    public DrawingSummary rename(@PathVariable("id") String id,
                                 @Valid @RequestBody RenameDrawingRequest request) {
        return drawings.rename(id, request.getName());
    }

    @PutMapping("/{id}/preview")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void preview(@PathVariable("id") String id, @RequestBody PreviewRequest request) {
        drawings.updatePreview(id, request.getPreview());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        drawings.delete(id);
    }
}
