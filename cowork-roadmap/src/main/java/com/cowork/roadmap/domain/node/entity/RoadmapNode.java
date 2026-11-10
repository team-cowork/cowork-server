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

    /** 본문 최대 길이. UTF-16 코드 단위로 센다. */
    public static final int MAX_CONTENT_LENGTH = 100_000;

    /** 트리 조회와 계층 연산이 한 번에 처리하는 노드 수의 상한. */
    public static final int MAX_NODES_PER_ROADMAP = 1_000;

    /**
     * 루트를 1로 센 트리 깊이의 제품 상한. 노드 한 단계가 트리 응답의 JSON 깊이를 2씩 늘리므로, Jackson 쓰기 깊이 기본값
     * 500이 허용하는 약 249단계보다 넉넉히 작게 둔다.
     */
    public static final int MAX_TREE_DEPTH = 50;

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
