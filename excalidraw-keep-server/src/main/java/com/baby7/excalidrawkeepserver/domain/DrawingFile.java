package com.baby7.excalidrawkeepserver.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Lob;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 画布里的图片附件。fileId 是 Excalidraw 自己算出来的内容哈希（SHA-1，40 位 hex），
 * 所以上传天然幂等：同一个 fileId 必然对应同一份内容。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "drawing_file")
public class DrawingFile {

    @EmbeddedId
    private DrawingFileId id;

    @Column(name = "mime_type", length = 255, nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** storage = "db" 时有效。 */
    @Lob
    @Column(name = "content")
    private byte[] content;

    /** db | fs | s3 | mongo，为以后把大附件挪出数据库留的口子。 */
    @Column(name = "storage", length = 16, nullable = false)
    private String storage = "db";

    /** storage != "db" 时的外部键。 */
    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Embeddable
    public static class DrawingFileId implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "drawing_id", length = 36, nullable = false)
        private String drawingId;

        @Column(name = "file_id", length = 40, nullable = false)
        private String fileId;

        public DrawingFileId(String drawingId, String fileId) {
            this.drawingId = drawingId;
            this.fileId = fileId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DrawingFileId)) {
                return false;
            }
            DrawingFileId that = (DrawingFileId) o;
            return Objects.equals(drawingId, that.drawingId) && Objects.equals(fileId, that.fileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(drawingId, fileId);
        }
    }
}
