package com.baby7.excalidrawkeepserver.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一个画布（场景）。elements / appState 以 JSON 文本整体存取：
 * 画布永远是"整读整写"，拆成行既无查询需求又拖慢保存。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "drawing", indexes = {
        @Index(name = "idx_drawing_updated", columnList = "updated_at"),
        @Index(name = "idx_drawing_deleted", columnList = "deleted_at")
})
public class Drawing {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    /** Excalidraw 的 elements 数组，JSON 文本。 */
    @Lob
    @Column(name = "elements", nullable = false)
    private String elements;

    /** 白名单后的 appState，JSON 对象。 */
    @Lob
    @Column(name = "app_state", nullable = false)
    private String appState;

    /** 列表页缩略图，data:image/webp;base64,... 格式。 */
    @Lob
    @Column(name = "preview")
    private String preview;

    /**
     * 场景内容版本号，只作为并发保存的乐观锁标识。
     * 故意不加 @Version：改名/换缩略图不该让正在编辑的标签页报 409，
     * 版本只在场景真正保存时由 updateScene 的 CAS 语句自增。
     */
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 软删标记，非空即视为已删除。 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static Drawing empty(String name) {
        Drawing drawing = new Drawing();
        drawing.id = UUID.randomUUID().toString();
        drawing.name = name;
        drawing.elements = "[]";
        drawing.appState = "{}";
        drawing.version = 0;
        Instant now = Instant.now();
        drawing.createdAt = now;
        drawing.updatedAt = now;
        return drawing;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
