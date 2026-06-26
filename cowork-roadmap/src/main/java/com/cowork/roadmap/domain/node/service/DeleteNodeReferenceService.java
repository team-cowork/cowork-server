package com.cowork.roadmap.domain.node.service;

import reactor.core.publisher.Mono;

public interface DeleteNodeReferenceService {

    Mono<Void> execute(Long userId, String userRole, Long referenceId);
}
