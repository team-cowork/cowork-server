package com.cowork.roadmap.domain.node.service;

import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;

import reactor.core.publisher.Flux;

public interface ListNodeReferencesService {

    Flux<NodeReferenceResDto> execute(Long userId, String userRole, Long nodeId);
}
