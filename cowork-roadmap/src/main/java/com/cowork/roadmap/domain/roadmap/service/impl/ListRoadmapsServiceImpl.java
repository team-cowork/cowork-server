package com.cowork.roadmap.domain.roadmap.service.impl;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.ListRoadmapsService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import lombok.RequiredArgsConstructor;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class ListRoadmapsServiceImpl implements ListRoadmapsService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;

    @Override
    @Transactional(readOnly = true)
    public List<RoadmapResDto> execute(Long userId,
            String userRole,
            RoadmapScope scope,
            String category,
            Long teamId,
            Long projectId) {
        if (teamId != null && projectId != null) {
            throw new ExpectedException("teamId와 projectId는 동시에 지정할 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
        // ?category= 처럼 빈 값은 필터 없음으로 본다. 생성 시 category는 @NotBlank라 빈 값으로 거르면 항상 빈 목록이
        // 된다.
        String categoryFilter = category == null || category.isBlank() ? null : category;
        if (teamId != null) {
            requireScope(scope, RoadmapScope.TEAM, "teamId");
            accessGuard.requireTeamReadable(teamId, userId, userRole);
            return roadmapRepository.findListByOwnerTeamId(teamId, categoryFilter)
                    .stream()
                    .map(RoadmapResDto::from)
                    .toList();
        }
        if (projectId != null) {
            requireScope(scope, RoadmapScope.PROJECT, "projectId");
            return roadmapRepository.findListByOwnerProjectId(projectId, categoryFilter)
                    .stream()
                    .filter(roadmap -> accessGuard.canReadTeam(roadmap.getOwnerTeamId(), userId, userRole))
                    .map(RoadmapResDto::from)
                    .toList();
        }
        RoadmapScope target = scope == null ? RoadmapScope.GLOBAL : scope;
        if (target != RoadmapScope.GLOBAL) {
            throw new ExpectedException("커스텀 로드맵 목록에는 teamId 또는 projectId가 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        List<Roadmap> roadmaps = roadmapRepository.findListByScope(target.name(), categoryFilter);
        return roadmaps.stream().map(RoadmapResDto::from).toList();
    }

    private static void requireScope(RoadmapScope scope, RoadmapScope expected, String ownerParam) {
        if (scope != null && scope != expected) {
            throw new ExpectedException(ownerParam + "로 조회할 때 scope는 " + expected.name() + "만 지정할 수 있습니다.",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
