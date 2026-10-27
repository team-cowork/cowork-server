package com.cowork.roadmap.domain.node.service;

import java.util.List;

import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;

public interface ListNodeReferencesService {

    List<NodeReferenceResDto> execute(Long userId, String userRole, Long nodeId);
}
