package com.cowork.roadmap.domain.roadmap.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.BaseEntity;

@Table("tb_roadmaps")
public class Roadmap extends BaseEntity {

    @Id
    private Long id;

    @Column("title")
    private String title;

    @Column("description")
    private String description;

    @Column("category")
    private String category;

    @Column("scope")
    private String scope;

    @Column("owner_team_id")
    private Long ownerTeamId;

    @Column("owner_project_id")
    private Long ownerProjectId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Long getOwnerTeamId() {
        return ownerTeamId;
    }

    public void setOwnerTeamId(Long ownerTeamId) {
        this.ownerTeamId = ownerTeamId;
    }

    public Long getOwnerProjectId() {
        return ownerProjectId;
    }

    public void setOwnerProjectId(Long ownerProjectId) {
        this.ownerProjectId = ownerProjectId;
    }
}
