package com.baby7.excalidrawkeepserver.repo;

import com.baby7.excalidrawkeepserver.domain.DrawingFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DrawingFileRepository extends JpaRepository<DrawingFile, DrawingFile.DrawingFileId> {

    @Query("select f from DrawingFile f where f.id.drawingId = :drawingId order by f.createdAt asc")
    List<DrawingFile> findAllByDrawingId(@Param("drawingId") String drawingId);

    @Query("select f.id.fileId from DrawingFile f where f.id.drawingId = :drawingId")
    List<String> findFileIds(@Param("drawingId") String drawingId);

    @Modifying
    @Query("delete from DrawingFile f where f.id.drawingId = :drawingId")
    int deleteByDrawingId(@Param("drawingId") String drawingId);
}
