package com.cowork.roadmap.domain.roadmap.entity;

import com.cowork.roadmap.global.audit.BaseEntity;

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

@Getter
@Entity
@Table(name = "tb_roadmaps")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Roadmap extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    @Column(name = "owner_team_id")
    private Long ownerTeamId;

    @Column(name = "owner_project_id")
    private Long ownerProjectId;

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
