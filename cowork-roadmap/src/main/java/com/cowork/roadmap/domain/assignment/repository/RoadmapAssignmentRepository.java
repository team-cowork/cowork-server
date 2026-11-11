package com.cowork.roadmap.domain.assignment.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;

public interface RoadmapAssignmentRepository extends JpaRepository<RoadmapAssignment, Long> {

    List<RoadmapAssignment> findByAssigneeUserIdOrderByIdDesc(Long assigneeUserId);

    List<RoadmapAssignment> findByRoadmapIdOrderByIdDesc(Long roadmapId);

    long countByRoadmapIdAndNodeIdIn(Long roadmapId, Collection<Long> nodeIds);

    /**
     * 유니크 키 (roadmap_id, node_key, assignee_user_id)와 같은 기준으로 과제를 센다. node_key는
     * 엔티티에 매핑하지 않은 생성 컬럼이다.
     */
    @Query(value = "SELECT COUNT(*) FROM tb_roadmap_assignments WHERE roadmap_id = :roadmapId AND node_key = :nodeKey AND assignee_user_id = :assigneeUserId", nativeQuery = true)
    long countByRoadmapIdAndNodeKeyAndAssigneeUserId(Long roadmapId, Long nodeKey, Long assigneeUserId);
}
