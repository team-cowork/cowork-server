package com.cowork.roadmap.domain.node.service;

public interface DeleteNodeReferenceService {

    void execute(Long userId, String userRole, Long referenceId);
}
