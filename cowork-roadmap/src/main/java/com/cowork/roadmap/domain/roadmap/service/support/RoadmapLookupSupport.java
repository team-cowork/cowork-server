package com.cowork.roadmap.domain.roadmap.service.support;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;

import lombok.RequiredArgsConstructor;
import team.themoment.sdk.exception.ExpectedException;

@Component
@RequiredArgsConstructor
public class RoadmapLookupSupport {

    private final RoadmapRepository roadmapRepository;

    public Roadmap findRoadmapOrThrow(Long roadmapId) {
        return roadmapRepository.findById(roadmapId)
                .orElseThrow(() -> new ExpectedException("로드맵을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    public Roadmap findRoadmapForUpdateOrThrow(Long roadmapId) {
        return roadmapRepository.findByIdForUpdate(roadmapId)
                .orElseThrow(() -> new ExpectedException("로드맵을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }
}
