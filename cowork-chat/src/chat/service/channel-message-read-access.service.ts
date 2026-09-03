import { ForbiddenException, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { Server } from 'socket.io';
import { ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';
import { ChannelMemberRepository } from '../repository/channel-member.repository';
import { ChannelProjectionRepository, ChannelProjectionView } from '../repository/channel-projection.repository';
import { ChannelRolePolicyProjectionRepository } from '../repository/channel-role-policy-projection.repository';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';
import { TeamRoleProjectionRepository } from '../repository/team-role-projection.repository';

export interface ChannelReadAccessRequest {
    channelId: number;
    userId: number;
}

type AccessMode = 'MESSAGE_READ' | 'CHANNEL_METADATA';

const accessKey = (channelId: number, userId: number) => `${channelId}:${userId}`;
const teamUserKey = (teamId: number, userId: number) => `${teamId}:${userId}`;
const policyKey = (channelId: number, roleId: number) => `${channelId}:${roleId}`;

/**
 * role×channel `message_read` 정책의 단일 평가 지점.
 *
 * custom role의 priority가 높은 단계부터 평가되며, 같은 priority에서 충돌하면 deny가 이긴다.
 * 어떤 role에도 명시값이 없으면 deny한다. built-in OWNER는 role policy만 우회한다.
 * 메시지 본문/실시간 접근은 공개 여부와 관계없이 active 채널 멤버십이 필요하며,
 * 채널 메타데이터는 공개 채널만 멤버십 없이 볼 수 있다.
 */
@Injectable()
export class ChannelMessageReadAccessService {
    constructor(
        private readonly channelRepository: ChannelProjectionRepository,
        private readonly channelMemberRepository: ChannelMemberRepository,
        private readonly teamMemberRepository: TeamMemberProjectionRepository,
        private readonly teamRoleRepository: TeamRoleProjectionRepository,
        private readonly policyRepository: ChannelRolePolicyProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    async canReadChannel(channelId: number, userId: number): Promise<boolean> {
        const result = await this.evaluateMany([{ channelId, userId }]);
        return result.get(accessKey(channelId, userId)) ?? false;
    }

    async requireCanRead(channelId: number, userId: number): Promise<void> {
        if (!this.projectionReadiness.isReady()) {
            throw new ServiceUnavailableException('권한 정보가 아직 동기화되지 않았습니다');
        }
        if (!(await this.canReadChannel(channelId, userId))) {
            throw new ForbiddenException('채널 메시지 읽기 권한이 없습니다');
        }
    }

    async filterReadableChannelIds(_teamId: number, userId: number, channelIds: number[]): Promise<number[]> {
        const uniqueChannelIds = [...new Set(channelIds)];
        const result = await this.evaluateMany(uniqueChannelIds.map((channelId) => ({ channelId, userId })));
        return uniqueChannelIds.filter((channelId) => result.get(accessKey(channelId, userId)) === true);
    }

    async filterVisibleChannelIds(_teamId: number, userId: number, channelIds: number[]): Promise<number[]> {
        const uniqueChannelIds = [...new Set(channelIds)];
        const result = await this.evaluateVisibilityMany(
            uniqueChannelIds.map((channelId) => ({ channelId, userId })),
        );
        return uniqueChannelIds.filter((channelId) => result.get(accessKey(channelId, userId)) === true);
    }

    async findReadableTeamChannelIds(teamId: number, userId: number): Promise<number[]> {
        const channelIds = await this.channelRepository.findIdsByTeamId(teamId);
        return this.filterReadableChannelIds(teamId, userId, channelIds);
    }

    async findReadableProjectChannelIds(projectId: number, userId: number): Promise<number[]> {
        const channelIds = await this.channelRepository.findIdsByProjectId(projectId);
        return this.filterReadableChannelIds(0, userId, channelIds);
    }

    async filterReadableUsersByChannel(
        usersByChannel: Map<number, number[]>,
    ): Promise<Map<number, number[]>> {
        const requests = [...usersByChannel].flatMap(([channelId, userIds]) =>
            [...new Set(userIds)].map((userId) => ({ channelId, userId })));
        const result = await this.evaluateMany(requests);
        return new Map([...usersByChannel].map(([channelId, userIds]) => [
            channelId,
            [...new Set(userIds)].filter((userId) => result.get(accessKey(channelId, userId)) === true),
        ]));
    }

    /** 현재 연결된 socket 중 회수된 message_read 권한을 더 이상 갖지 않는 socket을 room에서 제거한다. */
    async evictUnauthorizedSockets(
        io: Server,
        channelIds: number[],
        userIds?: number[],
    ): Promise<void> {
        const uniqueChannelIds = [...new Set(channelIds)];
        if (uniqueChannelIds.length === 0) return;
        const rooms = uniqueChannelIds.map((channelId) => `chat:${channelId}`);
        const sockets = await io.in(rooms).fetchSockets();
        const affectedUsers = userIds ? new Set(userIds) : undefined;
        const socketsWithChannels = sockets.flatMap((socket) => {
            const userId = this.socketUserId(socket);
            if (userId === null || (affectedUsers && !affectedUsers.has(userId))) return [];
            return uniqueChannelIds
                .filter((channelId) => socket.rooms.has(`chat:${channelId}`))
                .map((channelId) => ({ socket, channelId, userId }));
        });
        const access = await this.evaluateMany(
            socketsWithChannels.map(({ channelId, userId }) => ({ channelId, userId })),
        );
        for (const { socket, channelId, userId } of socketsWithChannels) {
            if (access.get(accessKey(channelId, userId)) === true) continue;
            socket.leave(`chat:${channelId}`);
            socket.emit('channel:access:revoked', { channelId });
        }
    }

    async emitToReadableChannelUsers(
        io: Server | undefined,
        channelId: number,
        event: string,
        payload: unknown,
        excludedSocketId?: string,
    ): Promise<void> {
        if (!io) return;
        const sockets = await io.in(`chat:${channelId}`).fetchSockets();
        const users = sockets
            .map((socket) => this.socketUserId(socket))
            .filter((userId): userId is number => userId !== null);
        const readableUsers = new Set(
            (await this.filterReadableUsersByChannel(new Map([[channelId, users]]))).get(channelId) ?? [],
        );
        for (const socket of sockets) {
            if (socket.id === excludedSocketId) continue;
            const userId = this.socketUserId(socket);
            if (userId !== null && readableUsers.has(userId)) socket.emit(event, payload);
        }
    }

    async emitChannelEventToVisibleTeamUsers(
        io: Server,
        teamId: number,
        channelId: number,
        event: string,
        payload: unknown,
    ): Promise<void> {
        const socketIds = await this.captureVisibleTeamSocketIds(io, teamId, channelId);
        for (const socketId of socketIds) io.to(socketId).emit(event, payload);
    }

    /** stale team room 구독자를 제외하고 현재 active 팀 멤버에게만 팀 범위 metadata를 전송한다. */
    async emitToActiveTeamUsers(
        io: Server,
        teamId: number,
        event: string,
        payload: unknown,
    ): Promise<void> {
        if (!this.projectionReadiness.isReady()) return;
        const sockets = await io.in(`team:${teamId}`).fetchSockets();
        const userIds = [...new Set(sockets
            .map((socket) => this.socketUserId(socket))
            .filter((userId): userId is number => userId !== null))];
        const members = await this.teamMemberRepository.findByTeamIdsAndUserIds([teamId], userIds);
        const activeUserIds = new Set(members
            .filter((member) => member.teamId === teamId)
            .map((member) => member.userId));
        for (const socket of sockets) {
            const userId = this.socketUserId(socket);
            if (userId !== null && activeUserIds.has(userId)) socket.emit(event, payload);
        }
    }

    /** 삭제 전 projection으로 대상 socket을 캡처해 삭제 후에도 비공개 metadata 수신자를 제한한다. */
    async captureVisibleTeamSocketIds(
        io: Server,
        teamId: number,
        channelId: number,
    ): Promise<string[]> {
        const sockets = await io.in(`team:${teamId}`).fetchSockets();
        const users = sockets
            .map((socket) => this.socketUserId(socket))
            .filter((userId): userId is number => userId !== null);
        const access = await this.evaluateVisibilityMany(
            [...new Set(users)].map((userId) => ({ channelId, userId })),
        );
        return sockets
            .filter((socket) => {
                const userId = this.socketUserId(socket);
                return userId !== null && access.get(accessKey(channelId, userId)) === true;
            })
            .map((socket) => socket.id);
    }

    async evaluateMany(requests: ChannelReadAccessRequest[]): Promise<Map<string, boolean>> {
        return this.evaluate(requests, 'MESSAGE_READ');
    }

    async evaluateVisibilityMany(requests: ChannelReadAccessRequest[]): Promise<Map<string, boolean>> {
        return this.evaluate(requests, 'CHANNEL_METADATA');
    }

    private async evaluate(
        requests: ChannelReadAccessRequest[],
        mode: AccessMode,
    ): Promise<Map<string, boolean>> {
        const uniqueRequests = [...new Map(
            requests.map((request) => [accessKey(request.channelId, request.userId), request]),
        ).values()];
        const result = new Map(uniqueRequests.map((request) => [accessKey(request.channelId, request.userId), false]));
        if (uniqueRequests.length === 0 || !this.projectionReadiness.isReady()) return result;

        const channelIds = [...new Set(uniqueRequests.map(({ channelId }) => channelId))];
        const userIds = [...new Set(uniqueRequests.map(({ userId }) => userId))];
        const [channels, membersByChannel] = await Promise.all([
            this.channelRepository.findByIds(channelIds),
            this.channelMemberRepository.findByChannelIdsAndUserIds(channelIds, userIds),
        ]);
        const channelById = new Map(channels.map((channel) => [channel.channelId, channel]));
        const memberKeys = new Set<string>();
        for (const [channelId, members] of membersByChannel) {
            for (const member of members) {
                const key = accessKey(channelId, member.userId);
                memberKeys.add(key);
            }
        }

        const teamRequests = uniqueRequests.flatMap((request) => {
            const channel = channelById.get(request.channelId);
            if (!channel) return [];
            if (this.isDm(channel)) {
                result.set(accessKey(request.channelId, request.userId), memberKeys.has(accessKey(request.channelId, request.userId)));
                return [];
            }
            if (channel.teamId === null || channel.type === 'DM') return [];
            return [{ request, channel, teamId: channel.teamId }];
        });
        if (teamRequests.length === 0) return result;

        const teamIds = [...new Set(teamRequests.map(({ teamId }) => teamId))];
        const teamMembers = await this.teamMemberRepository.findByTeamIdsAndUserIds(teamIds, userIds);
        const teamMemberByKey = new Map(teamMembers.map((member) => [teamUserKey(member.teamId, member.userId), member]));

        const policyCandidates = teamRequests.filter(({ request, channel, teamId }) => {
            const member = teamMemberByKey.get(teamUserKey(teamId, request.userId));
            if (!member) return false;
            const hasChannelMembership = memberKeys.has(accessKey(request.channelId, request.userId));
            if (mode === 'MESSAGE_READ' && !hasChannelMembership) return false;
            if (channel.isPrivate !== false && !hasChannelMembership) return false;
            if (member.role === 'OWNER') {
                result.set(accessKey(request.channelId, request.userId), true);
                return false;
            }
            return true;
        });
        if (policyCandidates.length === 0) return result;

        const accountIds = [...new Set(policyCandidates.map(({ request }) => request.userId))];
        const assignments = await this.teamRoleRepository.findAssignmentsByTeamIdsAndAccountIds(teamIds, accountIds);
        const roleIds = [...new Set(assignments.map(({ roleId }) => roleId))];
        const candidateChannelIds = [...new Set(policyCandidates.map(({ request }) => request.channelId))];
        const [roles, policies] = await Promise.all([
            this.teamRoleRepository.findRolesByIds(roleIds),
            this.policyRepository.findByChannelIdsAndRoleIds(candidateChannelIds, roleIds),
        ]);
        const roleById = new Map(roles.map((role) => [role.roleId, role]));
        const policyByKey = new Map(policies.map((policy) => [policyKey(policy.channelId, policy.roleId), policy]));
        const assignmentsByTeamUser = new Map<string, number[]>();
        for (const assignment of assignments) {
            const key = teamUserKey(assignment.teamId, assignment.accountId);
            const existing = assignmentsByTeamUser.get(key);
            if (existing) existing.push(assignment.roleId);
            else assignmentsByTeamUser.set(key, [assignment.roleId]);
        }

        for (const { request, teamId } of policyCandidates) {
            const assignedRoleIds = assignmentsByTeamUser.get(teamUserKey(teamId, request.userId)) ?? [];
            let selectedPriority: number | undefined;
            let selected = false;
            for (const roleId of assignedRoleIds) {
                const role = roleById.get(roleId);
                const policy = policyByKey.get(policyKey(request.channelId, roleId));
                if (!role || role.teamId !== teamId || !policy || policy.teamId !== teamId) continue;
                if (selectedPriority === undefined || role.priority > selectedPriority) {
                    selectedPriority = role.priority;
                    selected = policy.messageRead;
                } else if (role.priority === selectedPriority && policy.messageRead === false) {
                    selected = false;
                }
            }
            result.set(accessKey(request.channelId, request.userId), selectedPriority !== undefined && selected);
        }
        return result;
    }

    private isDm(channel: ChannelProjectionView): boolean {
        return channel.teamId === null && channel.type === 'DM';
    }

    private socketUserId(socket: { data: unknown }): number | null {
        if (typeof socket.data !== 'object' || socket.data === null) return null;
        const rawUserId = (socket.data as Record<string, unknown>).userId;
        const userId = typeof rawUserId === 'number' || typeof rawUserId === 'string'
            ? Number(rawUserId)
            : Number.NaN;
        return Number.isSafeInteger(userId) && userId > 0 ? userId : null;
    }
}
