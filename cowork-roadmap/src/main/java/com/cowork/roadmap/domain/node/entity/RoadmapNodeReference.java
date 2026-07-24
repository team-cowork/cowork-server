package com.cowork.roadmap.domain.node.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.TimestampEntity;

import lombok.Builder;
import lombok.Getter;

/** 노드에 딸린 관련 자료 링크. */
@Getter
@Table("tb_roadmap_node_references")
public class RoadmapNodeReference extends TimestampEntity {

    @Id
    private final Long id;

    @Column("node_id")
    private final Long nodeId;

    @Column("title")
    private final String title;

    @Column("url")
    private final String url;

    @Column("position")
    private final Integer position;

    @Builder(toBuilder = true)
    public RoadmapNodeReference(Long id, Long nodeId, String title, String url, Integer position) {
        this.id = id;
        this.nodeId = nodeId;
        this.title = title;
        this.url = url;
        this.position = position;
    }
}
