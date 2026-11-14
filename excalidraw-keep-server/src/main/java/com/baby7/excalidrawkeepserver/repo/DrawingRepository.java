package com.baby7.excalidrawkeepserver.repo;

import com.baby7.excalidrawkeepserver.domain.Drawing;
import com.baby7.excalidrawkeepserver.dto.DrawingSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface DrawingRepository extends JpaRepository<Drawing, String> {

    String SUMMARY_COLUMNS = "select new com.baby7.excalidrawkeepserver.dto.DrawingSummary("
            + "d.id, d.name, d.preview, d.version, d.updatedAt) from Drawing d ";

    @Query(value = SUMMARY_COLUMNS + "where d.deletedAt is null order by d.updatedAt desc",
            countQuery = "select count(d) from Drawing d where d.deletedAt is null")
    Page<DrawingSummary> findSummaries(Pageable pageable);

    @Query(value = SUMMARY_COLUMNS
            + "where d.deletedAt is null and lower(d.name) like :pattern order by d.updatedAt desc",
            countQuery = "select count(d) from Drawing d where d.deletedAt is null and lower(d.name) like :pattern")
    Page<DrawingSummary> searchSummaries(@Param("pattern") String pattern, Pageable pageable);

    @Query(SUMMARY_COLUMNS + "where d.id = :id and d.deletedAt is null")
    Optional<DrawingSummary> findMetaById(@Param("id") String id);

    @Query("select d from Drawing d where d.id = :id and d.deletedAt is null")
    Optional<Drawing> findActiveById(@Param("id") String id);

    @Query("select d.version from Drawing d where d.id = :id and d.deletedAt is null")
    Optional<Integer> findVersionById(@Param("id") String id);

    /**
     * 场景保存的唯一写入口：单条 CAS 语句，只有版本号对得上才写入并自增。
     * 返回 0 表示客户端拿的是旧版本（或画布已被删）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Drawing d set d.elements = :elements, d.appState = :appState, "
            + "d.version = d.version + 1, d.updatedAt = :now "
            + "where d.id = :id and d.version = :baseVersion and d.deletedAt is null")
    int updateScene(@Param("id") String id,
                    @Param("baseVersion") int baseVersion,
                    @Param("elements") String elements,
                    @Param("appState") String appState,
                    @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Drawing d set d.name = :name, d.updatedAt = :now "
            + "where d.id = :id and d.deletedAt is null")
    int rename(@Param("id") String id, @Param("name") String name, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Drawing d set d.preview = :preview, d.updatedAt = :now "
            + "where d.id = :id and d.deletedAt is null")
    int updatePreview(@Param("id") String id, @Param("preview") String preview, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Drawing d set d.deletedAt = :now, d.updatedAt = :now "
            + "where d.id = :id and d.deletedAt is null")
    int softDelete(@Param("id") String id, @Param("now") Instant now);
}
