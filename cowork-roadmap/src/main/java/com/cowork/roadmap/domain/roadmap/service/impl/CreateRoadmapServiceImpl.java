package com.cowork.roadmap.domain.roadmap.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.CreateRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class CreateRoadmapServiceImpl implements CreateRoadmapService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;

    public CreateRoadmapServiceImpl(RoadmapRepository roadmapRepository, RoadmapAccessGuard accessGuard) {
        this.roadmapRepository = roadmapRepository;
        this.accessGuard = accessGuard;
    }

    @Override
    @Transactional
    public Mono<RoadmapResDto> execute(Long userId, String userRole, CreateRoadmapReqDto request) {
        RoadmapScope scope = request.scope();
        if (scope != RoadmapScope.GLOBAL && request.ownerTeamId() == null) {
            return Mono.error(new ExpectedException("TEAM/PROJECT 로드맵에는 ownerTeamId가 필요합니다.", HttpStatus.BAD_REQUEST));
        }
        if (scope == RoadmapScope.PROJECT && request.ownerProjectId() == null) {
            return Mono.error(new ExpectedException("PROJECT 로드맵에는 ownerProjectId가 필요합니다.", HttpStatus.BAD_REQUEST));
        }

        return accessGuard.requireCreatable(userId, userRole, scope, request.ownerTeamId()).then(Mono.defer(() -> {
            Roadmap roadmap = new Roadmap();
            roadmap.setTitle(request.title());
            roadmap.setDescription(request.description());
            roadmap.setCategory(request.category());
            roadmap.setScope(scope.name());
            roadmap.setOwnerTeamId(scope == RoadmapScope.GLOBAL ? null : request.ownerTeamId());
            roadmap.setOwnerProjectId(scope == RoadmapScope.PROJECT ? request.ownerProjectId() : null);
            roadmap.setCreatedBy(userId);
            roadmap.setLastModifiedBy(userId);
            return roadmapRepository.save(roadmap).map(RoadmapResDto::from);
        }));
    }
}
