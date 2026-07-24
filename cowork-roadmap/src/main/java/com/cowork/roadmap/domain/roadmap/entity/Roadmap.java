package com.cowork.roadmap.domain.roadmap.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.BaseEntity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
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
}
