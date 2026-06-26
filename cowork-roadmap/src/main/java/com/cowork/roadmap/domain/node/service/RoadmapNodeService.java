package com.cowork.roadmap.domain.node.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.request.CreateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesReqDto;
import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class RoadmapNodeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;

    public RoadmapNodeService(RoadmapNodeRepository nodeRepository,
            RoadmapNodeReferenceRepository referenceRepository,
            RoadmapRepository roadmapRepository,
            RoadmapAccessGuard accessGuard) {
        this.nodeRepository = nodeRepository;
        this.referenceRepository = referenceRepository;
        this.roadmapRepository = roadmapRepository;
        this.accessGuard = accessGuard;
    }

    @Transactional
    public Mono<NodeResDto> createNode(Long userId, String userRole, Long roadmapId, CreateNodeReqDto request) {
        return findRoadmapOrThrow(roadmapId).flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                .then(validateParent(roadmapId, request.parentId()))
                .then(nextPosition(roadmapId, request.parentId()))
                .flatMap(position -> {
                    RoadmapNode node = new RoadmapNode();
                    node.setRoadmapId(roadmapId);
                    node.setParentId(request.parentId());
                    node.setTitle(request.title());
                    node.setContent(request.content());
                    node.setSourceUrl(request.sourceUrl());
                    node.setSourceTitle(request.sourceTitle());
                    node.setPosition(position);
                    node.setCreatedBy(userId);
                    node.setLastModifiedBy(userId);
                    return nodeRepository.save(node).map(saved -> NodeResDto.of(saved, List.of()));
                }));
    }

    public Mono<NodeResDto> getNode(Long userId, String userRole, Long nodeId) {
        return findNodeOrThrow(nodeId).flatMap(node -> findRoadmapOrThrow(node.getRoadmapId())
                .flatMap(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                        .then(loadReferences(nodeId))
                        .map(refs -> NodeResDto.of(node, refs))));
    }

    @Transactional
    public Mono<NodeResDto> updateNode(Long userId, String userRole, Long nodeId, UpdateNodeReqDto request) {
        return findNodeOrThrow(nodeId).flatMap(node -> findRoadmapOrThrow(node.getRoadmapId())
                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole).then(Mono.defer(() -> {
                    if (request.title() != null) {
                        node.setTitle(request.title());
                    }
                    if (request.content() != null) {
                        node.setContent(request.content());
                    }
                    if (request.sourceUrl() != null) {
                        node.setSourceUrl(request.sourceUrl());
                    }
                    if (request.sourceTitle() != null) {
                        node.setSourceTitle(request.sourceTitle());
                    }
                    node.setLastModifiedBy(userId);
                    return nodeRepository.save(node);
                })).flatMap(saved -> loadReferences(saved.getId()).map(refs -> NodeResDto.of(saved, refs)))));
    }

    @Transactional
    public Mono<Void> deleteNode(Long userId, String userRole, Long nodeId) {
        return findNodeOrThrow(nodeId).flatMap(node -> findRoadmapOrThrow(node.getRoadmapId())
                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                        .then(nodeRepository.findByRoadmapIdOrderByPositionAsc(node.getRoadmapId()).collectList())
                        .flatMap(all -> nodeRepository.deleteAllById(collectSubtreeIds(nodeId, all)))));
    }

    @Transactional
    public Mono<Void> reorderNodes(Long userId, String userRole, Long roadmapId, ReorderNodesReqDto request) {
        return findRoadmapOrThrow(roadmapId).flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                .then(nodeRepository.findByRoadmapIdOrderByPositionAsc(roadmapId).collectList())
                .flatMap(all -> {
                    List<RoadmapNode> siblings = all.stream()
                            .filter(n -> Objects.equals(n.getParentId(), request.parentId()))
                            .toList();
                    Set<Long> siblingIds = new HashSet<>();
                    siblings.forEach(n -> siblingIds.add(n.getId()));
                    Set<Long> requested = new HashSet<>(request.orderedNodeIds());
                    if (requested.size() != request.orderedNodeIds().size() || !requested.equals(siblingIds)) {
                        return Mono.error(new ExpectedException("orderedNodeIds는 해당 부모의 모든 자식을 중복 없이 정확히 포함해야 합니다.",
                                HttpStatus.BAD_REQUEST));
                    }
                    Map<Long, RoadmapNode> byId = new HashMap<>();
                    siblings.forEach(n -> byId.put(n.getId(), n));
                    List<RoadmapNode> updated = new ArrayList<>();
                    List<Long> ordered = request.orderedNodeIds();
                    for (int i = 0; i < ordered.size(); i++) {
                        RoadmapNode node = byId.get(ordered.get(i));
                        node.setPosition(i);
                        node.setLastModifiedBy(userId);
                        updated.add(node);
                    }
                    return nodeRepository.saveAll(updated).then();
                }));
    }

    public Flux<NodeReferenceResDto> listReferences(Long userId, String userRole, Long nodeId) {
        return findNodeOrThrow(nodeId)
                .flatMap(node -> findRoadmapOrThrow(node.getRoadmapId())
                        .flatMap(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole).thenReturn(node)))
                .flatMapMany(node -> referenceRepository.findByNodeIdOrderByPositionAsc(nodeId)
                        .map(NodeReferenceResDto::from));
    }

    @Transactional
    public Mono<NodeReferenceResDto> addReference(Long userId,
            String userRole,
            Long nodeId,
            NodeReferenceReqDto request) {
        return findNodeOrThrow(nodeId).flatMap(node -> findRoadmapOrThrow(node.getRoadmapId())
                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                        .then(referenceRepository.countByNodeId(nodeId))
                        .flatMap(count -> {
                            RoadmapNodeReference ref = new RoadmapNodeReference();
                            ref.setNodeId(nodeId);
                            ref.setTitle(request.title());
                            ref.setUrl(request.url());
                            ref.setPosition(count.intValue());
                            return referenceRepository.save(ref).map(NodeReferenceResDto::from);
                        })));
    }

    @Transactional
    public Mono<NodeReferenceResDto> updateReference(Long userId,
            String userRole,
            Long referenceId,
            NodeReferenceReqDto request) {
        return findReferenceOrThrow(referenceId).flatMap(
                ref -> findNodeOrThrow(ref.getNodeId()).flatMap(node -> findRoadmapOrThrow(node.getRoadmapId()).flatMap(
                        roadmap -> accessGuard.requireMutable(roadmap, userId, userRole).then(Mono.defer(() -> {
                            ref.setTitle(request.title());
                            ref.setUrl(request.url());
                            return referenceRepository.save(ref).map(NodeReferenceResDto::from);
                        })))));
    }

    @Transactional
    public Mono<Void> deleteReference(Long userId, String userRole, Long referenceId) {
        return findReferenceOrThrow(referenceId)
                .flatMap(ref -> findNodeOrThrow(ref.getNodeId()).flatMap(node -> findRoadmapOrThrow(node.getRoadmapId())
                        .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                                .then(referenceRepository.delete(ref)))));
    }

    private Mono<List<NodeReferenceResDto>> loadReferences(Long nodeId) {
        return referenceRepository.findByNodeIdOrderByPositionAsc(nodeId).map(NodeReferenceResDto::from).collectList();
    }

    private Mono<Void> validateParent(Long roadmapId, Long parentId) {
        if (parentId == null) {
            return Mono.empty();
        }
        return nodeRepository.findById(parentId)
                .switchIfEmpty(Mono.error(new ExpectedException("상위 노드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)))
                .flatMap(parent -> roadmapId.equals(parent.getRoadmapId())
                        ? Mono.empty()
                        : Mono.error(new ExpectedException("상위 노드가 같은 로드맵에 속하지 않습니다.", HttpStatus.BAD_REQUEST)))
                .then();
    }

    private Mono<Integer> nextPosition(Long roadmapId, Long parentId) {
        Mono<Long> count = parentId == null
                ? nodeRepository.countByRoadmapIdAndParentIdIsNull(roadmapId)
                : nodeRepository.countByRoadmapIdAndParentId(roadmapId, parentId);
        return count.map(Long::intValue);
    }

    /** rootId와 그 모든 후손 노드 id를 in-memory BFS로 수집한다. */
    private Set<Long> collectSubtreeIds(Long rootId, List<RoadmapNode> all) {
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (RoadmapNode node : all) {
            if (node.getParentId() != null) {
                childrenByParent.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node.getId());
            }
        }
        Set<Long> result = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (result.add(current)) {
                queue.addAll(childrenByParent.getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    private Mono<RoadmapNode> findNodeOrThrow(Long nodeId) {
        return nodeRepository.findById(nodeId)
                .switchIfEmpty(Mono.error(new ExpectedException("노드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }

    private Mono<RoadmapNodeReference> findReferenceOrThrow(Long referenceId) {
        return referenceRepository.findById(referenceId)
                .switchIfEmpty(Mono.error(new ExpectedException("관련 자료를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }

    private Mono<Roadmap> findRoadmapOrThrow(Long roadmapId) {
        return roadmapRepository.findById(roadmapId)
                .switchIfEmpty(Mono.error(new ExpectedException("로드맵을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }
}
