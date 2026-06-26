package com.cowork.roadmap.domain.node.presentation.data.response;

import java.util.List;

/** 트리 조회용 노드. children에 하위 노드가 재귀적으로 중첩된다. */
public record NodeTreeResDto(Long id, Long parentId, String title, String content, String sourceUrl, String sourceTitle,
        Integer position, List<NodeReferenceResDto> references, List<NodeTreeResDto> children) {
}
