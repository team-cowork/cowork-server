package com.cowork.roadmap.domain.node.service;

import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesReqDto;

public interface ReorderRoadmapNodesService {

    void execute(Long userId, String userRole, Long roadmapId, ReorderNodesReqDto request);
}
