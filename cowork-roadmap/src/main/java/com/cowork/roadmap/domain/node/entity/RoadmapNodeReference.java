package com.cowork.roadmap.domain.node.entity;

import com.cowork.roadmap.global.audit.TimestampEntity;

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

/** 노드에 딸린 관련 자료 링크. */
@Getter
@Entity
@Table(name = "tb_roadmap_node_references")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoadmapNodeReference extends TimestampEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Builder(toBuilder = true)
    public RoadmapNodeReference(Long id, Long nodeId, String title, String url, Integer position) {
        this.id = id;
        this.nodeId = nodeId;
        this.title = title;
        this.url = url;
        this.position = position;
    }
}
