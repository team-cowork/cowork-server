package com.cowork.roadmap.domain.node.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;

public interface RoadmapNodeRepository extends JpaRepository<RoadmapNode, Long> {

    List<RoadmapNode> findByRoadmapIdOrderByPositionAsc(Long roadmapId);

    // 잠금 전에 엔티티를 캐시에 올리지 않아 잠금 이후 조회가 최신 상태를 읽게 한다.
    @Query("SELECT node.roadmapId FROM RoadmapNode node WHERE node.id = :id")
    Optional<Long> findRoadmapIdById(Long id);

    /** 삭제로 생긴 gap과 무관하게 기존 최댓값 다음 position을 반환한다. */
    @Query(value = "SELECT COALESCE(MAX(position) + 1, 0) FROM tb_roadmap_nodes WHERE roadmap_id = :roadmapId AND parent_id IS NULL", nativeQuery = true)
    Long findNextRootPosition(Long roadmapId);

    /** 삭제로 생긴 gap과 무관하게 같은 부모의 기존 최댓값 다음 position을 반환한다. */
    @Query(value = "SELECT COALESCE(MAX(position) + 1, 0) FROM tb_roadmap_nodes WHERE roadmap_id = :roadmapId AND parent_id = :parentId", nativeQuery = true)
    Long findNextChildPosition(Long roadmapId, Long parentId);

    /** 하위 노드·과제 생성이 삭제 중인 노드를 참조하지 않도록 노드 삭제의 배타 잠금과 직렬화한다. */
    @Query(value = "SELECT * FROM tb_roadmap_nodes WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<RoadmapNode> findByIdForShare(Long id);

    /** 잠금 읽기로 transaction snapshot 이후 커밋된 하위 노드까지 포함해 서브트리를 계산한다. */
    @Query(value = "SELECT * FROM tb_roadmap_nodes WHERE roadmap_id = :roadmapId FOR UPDATE", nativeQuery = true)
    List<RoadmapNode> findAllByRoadmapIdForUpdate(Long roadmapId);
}
