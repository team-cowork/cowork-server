package com.cowork.roadmap.domain.assignment.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.TimestampEntity;

/** 온보딩 과제: 로드맵(또는 특정 노드)을 팀/프로젝트 멤버에게 부여한다. */
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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRoadmapId() {
        return roadmapId;
    }

    public void setRoadmapId(Long roadmapId) {
        this.roadmapId = roadmapId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getAssigneeUserId() {
        return assigneeUserId;
    }

    public void setAssigneeUserId(Long assigneeUserId) {
        this.assigneeUserId = assigneeUserId;
    }

    public Long getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(Long assignedBy) {
        this.assignedBy = assignedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDateTime dueDate) {
        this.dueDate = dueDate;
    }
}
