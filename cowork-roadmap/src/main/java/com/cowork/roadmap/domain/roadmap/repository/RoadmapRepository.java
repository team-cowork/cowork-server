package com.cowork.roadmap.domain.roadmap.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;

public interface RoadmapRepository extends JpaRepository<Roadmap, Long> {

    /** category가 null이면 종류로 거르지 않는다. */
    @Query("SELECT r FROM Roadmap r WHERE r.scope = :scope AND (:category IS NULL OR r.category = :category) ORDER BY r.id DESC")
    List<Roadmap> findListByScope(String scope, String category);

    /** category가 null이면 종류로 거르지 않는다. */
    @Query("SELECT r FROM Roadmap r WHERE r.ownerTeamId = :ownerTeamId AND (:category IS NULL OR r.category = :category) ORDER BY r.id DESC")
    List<Roadmap> findListByOwnerTeamId(Long ownerTeamId, String category);

    /** category가 null이면 종류로 거르지 않는다. */
    @Query("SELECT r FROM Roadmap r WHERE r.ownerProjectId = :ownerProjectId AND (:category IS NULL OR r.category = :category) ORDER BY r.id DESC")
    List<Roadmap> findListByOwnerProjectId(Long ownerProjectId, String category);

    /**
     * 로드맵 수정, 노드 생성·수정·재정렬·삭제, 참고 자료와 과제 생성을 로드맵 단위로 직렬화한다. 모든 경로가 로드맵 행을 노드 행보다 먼저
     * 잠가 잠금 순서를 하나로 유지한다.
     */
    @Query(value = "SELECT * FROM tb_roadmaps WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Roadmap> findByIdForUpdate(Long id);
}
