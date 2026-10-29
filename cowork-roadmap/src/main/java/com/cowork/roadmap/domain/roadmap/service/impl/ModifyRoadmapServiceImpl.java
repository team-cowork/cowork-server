package com.cowork.roadmap.domain.roadmap.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.ModifyRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ModifyRoadmapServiceImpl implements ModifyRoadmapService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport lookupSupport;

    /** 저장이 요청에 없는 필드까지 전체 행을 덮어쓰므로 로드맵 행을 잠근 뒤 최신 값을 읽어 동시 수정이 유실되지 않게 한다. */
    @Override
    @Transactional
    public RoadmapResDto execute(Long userId, String userRole, Long roadmapId, UpdateRoadmapReqDto request) {
        Roadmap roadmap = lookupSupport.findRoadmapForUpdateOrThrow(roadmapId);
        accessGuard.requireMutable(roadmap, userId, userRole);
        Roadmap updated = roadmap.toBuilder()
                .title(request.title() != null ? request.title() : roadmap.getTitle())
                .description(request.description() != null ? request.description() : roadmap.getDescription())
                .category(request.category() != null ? request.category() : roadmap.getCategory())
                .build();
        updated.prepareUpdateFrom(roadmap);
        updated.setLastModifiedBy(userId);
        return RoadmapResDto.from(roadmapRepository.saveAndFlush(updated));
    }
}
