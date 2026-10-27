package com.cowork.roadmap.domain.assignment.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;

public interface RoadmapAssignmentRepository extends JpaRepository<RoadmapAssignment, Long> {

    List<RoadmapAssignment> findByAssigneeUserIdOrderByIdDesc(Long assigneeUserId);

    List<RoadmapAssignment> findByRoadmapIdOrderByIdDesc(Long roadmapId);

    /** 잠금 읽기로 transaction snapshot 이후 커밋된 과제까지 센다. */
    @Query(value = "SELECT COUNT(*) FROM tb_roadmap_assignments WHERE roadmap_id = :roadmapId AND node_id IN (:nodeIds) FOR SHARE", nativeQuery = true)
    Long countByRoadmapIdAndNodeIdInForShare(Long roadmapId, Collection<Long> nodeIds);
}
