package com.cowork.roadmap.domain.roadmap.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeTreeResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapTreeResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class RoadmapService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;

    public RoadmapService(RoadmapRepository roadmapRepository,
            RoadmapNodeRepository nodeRepository,
            RoadmapNodeReferenceRepository referenceRepository,
            RoadmapAccessGuard accessGuard) {
        this.roadmapRepository = roadmapRepository;
        this.nodeRepository = nodeRepository;
        this.referenceRepository = referenceRepository;
        this.accessGuard = accessGuard;
    }

    @Transactional
    public Mono<RoadmapResDto> createRoadmap(Long userId, String userRole, CreateRoadmapReqDto request) {
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

    public Mono<RoadmapResDto> getRoadmap(Long userId, String userRole, Long roadmapId) {
        return findRoadmapOrThrow(roadmapId).flatMap(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                .thenReturn(RoadmapResDto.from(roadmap)));
    }

    public Flux<RoadmapResDto> listRoadmaps(RoadmapScope scope, String category, Long teamId, Long projectId) {
        Flux<Roadmap> roadmaps;
        if (projectId != null) {
            roadmaps = roadmapRepository.findByOwnerProjectIdOrderByIdDesc(projectId);
        } else if (teamId != null) {
            roadmaps = roadmapRepository.findByOwnerTeamIdOrderByIdDesc(teamId);
        } else {
            RoadmapScope target = scope == null ? RoadmapScope.GLOBAL : scope;
            roadmaps = category == null
                    ? roadmapRepository.findByScopeOrderByIdDesc(target.name())
                    : roadmapRepository.findByScopeAndCategoryOrderByIdDesc(target.name(), category);
        }
        return roadmaps.map(RoadmapResDto::from);
    }

    public Mono<RoadmapTreeResDto> getRoadmapTree(Long userId, String userRole, Long roadmapId) {
        return findRoadmapOrThrow(roadmapId).flatMap(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                .then(nodeRepository.findByRoadmapIdOrderByPositionAsc(roadmapId).collectList())
                .flatMap(nodes -> {
                    if (nodes.isEmpty()) {
                        return Mono.just(new RoadmapTreeResDto(RoadmapResDto.from(roadmap), List.of()));
                    }
                    List<Long> nodeIds = nodes.stream().map(RoadmapNode::getId).toList();
                    return referenceRepository.findByNodeIdInOrderByNodeIdAscPositionAsc(nodeIds)
                            .collectList()
                            .map(refs -> new RoadmapTreeResDto(RoadmapResDto.from(roadmap), assembleTree(nodes, refs)));
                }));
    }

    @Transactional
    public Mono<RoadmapResDto> updateRoadmap(Long userId,
            String userRole,
            Long roadmapId,
            UpdateRoadmapReqDto request) {
        return findRoadmapOrThrow(roadmapId)
                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole).then(Mono.defer(() -> {
                    if (request.title() != null) {
                        roadmap.setTitle(request.title());
                    }
                    if (request.description() != null) {
                        roadmap.setDescription(request.description());
                    }
                    if (request.category() != null) {
                        roadmap.setCategory(request.category());
                    }
                    roadmap.setLastModifiedBy(userId);
                    return roadmapRepository.save(roadmap).map(RoadmapResDto::from);
                })));
    }

    @Transactional
    public Mono<Void> deleteRoadmap(Long userId, String userRole, Long roadmapId) {
        return findRoadmapOrThrow(roadmapId).flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                .then(roadmapRepository.delete(roadmap)));
    }

    private Mono<Roadmap> findRoadmapOrThrow(Long roadmapId) {
        return roadmapRepository.findById(roadmapId)
                .switchIfEmpty(Mono.error(new ExpectedException("로드맵을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }

    /** position 순으로 정렬된 평면 노드 목록을 parent_id 기준으로 중첩 트리로 조립한다. */
    private List<NodeTreeResDto> assembleTree(List<RoadmapNode> nodes, List<RoadmapNodeReference> refs) {
        List<RoadmapNode> roots = new ArrayList<>();
        Map<Long, List<RoadmapNode>> childrenByParent = new HashMap<>();
        for (RoadmapNode node : nodes) {
            if (node.getParentId() == null) {
                roots.add(node);
            } else {
                childrenByParent.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            }
        }

        Map<Long, List<NodeReferenceResDto>> refsByNode = new HashMap<>();
        for (RoadmapNodeReference ref : refs) {
            refsByNode.computeIfAbsent(ref.getNodeId(), k -> new ArrayList<>()).add(NodeReferenceResDto.from(ref));
        }

        return roots.stream().map(root -> buildSubtree(root, childrenByParent, refsByNode)).toList();
    }

    private NodeTreeResDto buildSubtree(RoadmapNode node,
            Map<Long, List<RoadmapNode>> childrenByParent,
            Map<Long, List<NodeReferenceResDto>> refsByNode) {
        List<NodeTreeResDto> children = childrenByParent.getOrDefault(node.getId(), List.of())
                .stream()
                .map(child -> buildSubtree(child, childrenByParent, refsByNode))
                .toList();
        return new NodeTreeResDto(node.getId(),
                node.getParentId(),
                node.getTitle(),
                node.getContent(),
                node.getSourceUrl(),
                node.getSourceTitle(),
                node.getPosition(),
                refsByNode.getOrDefault(node.getId(), List.of()),
                children);
    }
}
