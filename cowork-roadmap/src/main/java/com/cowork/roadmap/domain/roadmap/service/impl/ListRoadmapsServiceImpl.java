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
        if (teamId != null) {
            accessGuard.requireTeamReadable(teamId, userId, userRole);
            return roadmapRepository.findByOwnerTeamIdOrderByIdDesc(teamId).stream().map(RoadmapResDto::from).toList();
        }
        if (projectId != null) {
            List<Roadmap> roadmaps = roadmapRepository.findByOwnerProjectIdOrderByIdDesc(projectId);
            roadmaps.forEach(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole));
            return roadmaps.stream().map(RoadmapResDto::from).toList();
        }
        RoadmapScope target = scope == null ? RoadmapScope.GLOBAL : scope;
        if (target != RoadmapScope.GLOBAL) {
            throw new ExpectedException("커스텀 로드맵 목록에는 teamId 또는 projectId가 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        List<Roadmap> roadmaps = category == null
                ? roadmapRepository.findByScopeOrderByIdDesc(target.name())
                : roadmapRepository.findByScopeAndCategoryOrderByIdDesc(target.name(), category);
        return roadmaps.stream().map(RoadmapResDto::from).toList();
    }
}
