package com.cowork.roadmap.domain.node.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.TimestampEntity;

/** 노드에 딸린 관련 자료 링크. */
@Table("tb_roadmap_node_references")
public class RoadmapNodeReference extends TimestampEntity {

    @Id
    private Long id;

    @Column("node_id")
    private Long nodeId;

    @Column("title")
    private String title;

    @Column("url")
    private String url;

    @Column("position")
    private Integer position;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }
}
