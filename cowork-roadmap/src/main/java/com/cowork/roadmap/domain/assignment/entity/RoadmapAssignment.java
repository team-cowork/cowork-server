package com.cowork.roadmap.domain.assignment.entity;

import java.time.LocalDateTime;

import com.cowork.roadmap.global.audit.TimestampEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 온보딩 과제: 로드맵(또는 특정 노드)을 팀/프로젝트 멤버에게 부여한다. */
@Getter
@Entity
@Table(name = "tb_roadmap_assignments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoadmapAssignment extends TimestampEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "roadmap_id", nullable = false)
    private Long roadmapId;

    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "assignee_user_id", nullable = false)
    private Long assigneeUserId;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Builder(toBuilder = true)
    public RoadmapAssignment(Long id,
            Long roadmapId,
            Long nodeId,
            String scope,
            Long teamId,
            Long projectId,
            Long assigneeUserId,
            Long assignedBy,
            String status,
            LocalDateTime dueDate) {
        this.id = id;
        this.roadmapId = roadmapId;
        this.nodeId = nodeId;
        this.scope = scope;
        this.teamId = teamId;
        this.projectId = projectId;
        this.assigneeUserId = assigneeUserId;
        this.assignedBy = assignedBy;
        this.status = status;
        this.dueDate = dueDate;
    }
}
