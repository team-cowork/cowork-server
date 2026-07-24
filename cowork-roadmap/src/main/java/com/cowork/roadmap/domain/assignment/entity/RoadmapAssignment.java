package com.cowork.roadmap.domain.assignment.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.TimestampEntity;

import lombok.Builder;
import lombok.Getter;

/** 온보딩 과제: 로드맵(또는 특정 노드)을 팀/프로젝트 멤버에게 부여한다. */
@Getter
@Builder(toBuilder = true)
@Table("tb_roadmap_assignments")
public class RoadmapAssignment extends TimestampEntity {

    @Id
    private final Long id;

    @Column("roadmap_id")
    private final Long roadmapId;

    @Column("node_id")
    private final Long nodeId;

    @Column("scope")
    private final String scope;

    @Column("team_id")
    private final Long teamId;

    @Column("project_id")
    private final Long projectId;

    @Column("assignee_user_id")
    private final Long assigneeUserId;

    @Column("assigned_by")
    private final Long assignedBy;

    @Column("status")
    private final String status;

    @Column("due_date")
    private final LocalDateTime dueDate;
}
