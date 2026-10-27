package com.cowork.roadmap.domain.node.service;

public interface DeleteRoadmapNodeService {

    void execute(Long userId, String userRole, Long nodeId);
}
