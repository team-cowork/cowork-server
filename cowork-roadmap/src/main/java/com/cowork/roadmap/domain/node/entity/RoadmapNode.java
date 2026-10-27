package com.cowork.roadmap.domain.node.entity;

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

/** 로드맵 트리의 노드. 노드 1개 = 문서 1개(제목/내용/원본 정보) + 관련자료 N개. */
@Getter
@Entity
@Table(name = "tb_roadmap_nodes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoadmapNode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "roadmap_id", nullable = false)
    private Long roadmapId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "source_title", length = 300)
    private String sourceTitle;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Builder(toBuilder = true)
    public RoadmapNode(Long id,
            Long roadmapId,
            Long parentId,
            String title,
            String content,
            String sourceUrl,
            String sourceTitle,
            Integer position) {
        this.id = id;
        this.roadmapId = roadmapId;
        this.parentId = parentId;
        this.title = title;
        this.content = content;
        this.sourceUrl = sourceUrl;
        this.sourceTitle = sourceTitle;
        this.position = position;
    }
}
