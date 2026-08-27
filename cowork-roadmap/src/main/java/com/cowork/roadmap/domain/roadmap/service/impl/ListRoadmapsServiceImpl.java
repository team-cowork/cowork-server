package com.cowork.roadmap.domain.roadmap.service.impl;

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
import reactor.core.publisher.Flux;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class ListRoadmapsServiceImpl implements ListRoadmapsService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;

    @Override
    @Transactional(readOnly = true)
    public Flux<RoadmapResDto> execute(Long userId,
            String userRole,
            RoadmapScope scope,
            String category,
            Long teamId,
            Long projectId) {
        if (teamId != null && projectId != null) {
            return Flux.error(new ExpectedException("teamId와 projectId는 동시에 지정할 수 없습니다.", HttpStatus.BAD_REQUEST));
        }
        if (teamId != null) {
            return accessGuard.requireTeamReadable(teamId, userId, userRole)
                    .thenMany(roadmapRepository.findByOwnerTeamIdOrderByIdDesc(teamId))
                    .map(RoadmapResDto::from);
        }

        if (projectId != null) {
            return roadmapRepository.findByOwnerProjectIdOrderByIdDesc(projectId)
                    .collectList()
                    .flatMapMany(roadmaps -> Flux.fromIterable(roadmaps)
                            .concatMap(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole))
                            .thenMany(Flux.fromIterable(roadmaps).map(RoadmapResDto::from)));
        }

        RoadmapScope target = scope == null ? RoadmapScope.GLOBAL : scope;
        if (target != RoadmapScope.GLOBAL) {
            return Flux
                    .error(new ExpectedException("커스텀 로드맵 목록에는 teamId 또는 projectId가 필요합니다.", HttpStatus.BAD_REQUEST));
        }
        Flux<Roadmap> roadmaps = category == null
                ? roadmapRepository.findByScopeOrderByIdDesc(target.name())
                : roadmapRepository.findByScopeAndCategoryOrderByIdDesc(target.name(), category);
        return roadmaps.map(RoadmapResDto::from);
    }
}
