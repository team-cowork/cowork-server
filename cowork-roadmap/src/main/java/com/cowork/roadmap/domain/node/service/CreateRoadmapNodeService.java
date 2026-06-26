package com.cowork.roadmap.domain.node.service;

import com.cowork.roadmap.domain.node.presentation.data.request.CreateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;

import reactor.core.publisher.Mono;

public interface CreateRoadmapNodeService {

    Mono<NodeResDto> execute(Long userId, String userRole, Long roadmapId, CreateNodeReqDto request);
}
