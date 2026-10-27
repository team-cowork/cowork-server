package com.cowork.roadmap.domain.node.service;

import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;

public interface QueryRoadmapNodeService {

    NodeResDto execute(Long userId, String userRole, Long nodeId);
}
