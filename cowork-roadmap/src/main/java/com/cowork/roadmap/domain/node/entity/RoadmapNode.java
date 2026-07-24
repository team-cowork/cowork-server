package com.cowork.roadmap.domain.node.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.BaseEntity;

import lombok.Getter;
import lombok.Setter;

/** 로드맵 트리의 노드. 노드 1개 = 문서 1개(제목/내용/원본 정보) + 관련자료 N개. */
@Getter
@Setter
@Table("tb_roadmap_nodes")
public class RoadmapNode extends BaseEntity {

    @Id
    private Long id;

    @Column("roadmap_id")
    private Long roadmapId;

    @Column("parent_id")
    private Long parentId;

    @Column("title")
    private String title;

    @Column("content")
    private String content;

    @Column("source_url")
    private String sourceUrl;

    @Column("source_title")
    private String sourceTitle;

    @Column("position")
    private Integer position;
}
