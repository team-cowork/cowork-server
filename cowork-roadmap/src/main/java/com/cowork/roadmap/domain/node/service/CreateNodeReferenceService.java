package com.cowork.roadmap.domain.node.service;

import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;

import reactor.core.publisher.Mono;

public interface CreateNodeReferenceService {

    Mono<NodeReferenceResDto> execute(Long userId, String userRole, Long nodeId, NodeReferenceReqDto request);
}
