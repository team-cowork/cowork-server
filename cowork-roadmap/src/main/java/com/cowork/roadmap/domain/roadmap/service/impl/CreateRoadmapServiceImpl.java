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

import lombok.RequiredArgsConstructor;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class CreateRoadmapServiceImpl implements CreateRoadmapService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;

    @Override
    @Transactional
    public RoadmapResDto execute(Long userId, String userRole, CreateRoadmapReqDto request) {
        RoadmapScope scope = request.scope();
        if (scope != RoadmapScope.GLOBAL && request.ownerTeamId() == null) {
            throw new ExpectedException("TEAM/PROJECT 로드맵에는 ownerTeamId가 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        // shortcut: 프로젝트 투영이 없어 ownerProjectId의 소속 팀을 확인할 수 없으므로 PROJECT 생성을 막는다. 투영을
        // 추가하면 팀 일치 검증으로 바꾼다.
        if (scope == RoadmapScope.PROJECT) {
            throw new ExpectedException("PROJECT 로드맵은 아직 생성할 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
        accessGuard.requireCreatable(userId, userRole, scope, request.ownerTeamId());
        Roadmap roadmap = Roadmap.builder()
                .title(request.title())
                .description(request.description())
                .category(request.category())
                .scope(scope.name())
                .ownerTeamId(scope == RoadmapScope.GLOBAL ? null : request.ownerTeamId())
                .build();
        roadmap.setCreatedBy(userId);
        roadmap.setLastModifiedBy(userId);
        return RoadmapResDto.from(roadmapRepository.saveAndFlush(roadmap));
    }
}
