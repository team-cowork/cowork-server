package com.cowork.roadmap.domain.roadmap.service;

public interface DeleteRoadmapService {

    void execute(Long userId, String userRole, Long roadmapId);
}
