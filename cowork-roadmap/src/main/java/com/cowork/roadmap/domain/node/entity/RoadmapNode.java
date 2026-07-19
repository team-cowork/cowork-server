package com.cowork.roadmap.domain.node.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.BaseEntity;

/** 로드맵 트리의 노드. 노드 1개 = 문서 1개(제목/내용/원본 정보) + 관련자료 N개. */
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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public void setSourceTitle(String sourceTitle) {
        this.sourceTitle = sourceTitle;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }
}
