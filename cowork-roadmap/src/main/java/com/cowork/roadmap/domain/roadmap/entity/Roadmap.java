package com.cowork.roadmap.domain.roadmap.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.BaseEntity;

import lombok.Builder;
import lombok.Getter;

@Getter
@Table("tb_roadmaps")
public class Roadmap extends BaseEntity {

    @Id
    private final Long id;

    @Column("title")
    private final String title;

    @Column("description")
    private final String description;

    @Column("category")
    private final String category;

    @Column("scope")
    private final String scope;

    @Column("owner_team_id")
    private final Long ownerTeamId;

    @Column("owner_project_id")
    private final Long ownerProjectId;

    @Builder(toBuilder = true)
    public Roadmap(Long id,
            String title,
            String description,
            String category,
            String scope,
            Long ownerTeamId,
            Long ownerProjectId) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.category = category;
        this.scope = scope;
        this.ownerTeamId = ownerTeamId;
        this.ownerProjectId = ownerProjectId;
    }
}
