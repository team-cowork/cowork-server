package com.cowork.roadmap.domain.assignment.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.TimestampEntity;

import lombok.Getter;
import lombok.Setter;

/** 온보딩 과제: 로드맵(또는 특정 노드)을 팀/프로젝트 멤버에게 부여한다. */
@Getter
@Setter
@Table("tb_roadmap_assignments")
public class RoadmapAssignment extends TimestampEntity {

    @Id
    private Long id;

    @Column("roadmap_id")
    private Long roadmapId;

    @Column("node_id")
    private Long nodeId;

    @Column("scope")
    private String scope;

    @Column("team_id")
    private Long teamId;

    @Column("project_id")
    private Long projectId;

    @Column("assignee_user_id")
    private Long assigneeUserId;

    @Column("assigned_by")
    private Long assignedBy;

    @Column("status")
    private String status;

    @Column("due_date")
    private LocalDateTime dueDate;
}
