package com.cowork.roadmap.domain.node.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.cowork.roadmap.global.audit.TimestampEntity;

import lombok.Getter;
import lombok.Setter;

/** 노드에 딸린 관련 자료 링크. */
@Getter
@Setter
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
}
