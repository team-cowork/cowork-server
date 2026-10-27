package com.cowork.roadmap.domain.node.service;

import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;

public interface ModifyRoadmapNodeService {

    NodeResDto execute(Long userId, String userRole, Long nodeId, UpdateNodeReqDto request);
}
