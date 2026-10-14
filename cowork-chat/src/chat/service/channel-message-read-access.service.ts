import { ForbiddenException, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { Server } from 'socket.io';
import { ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';
import {
    ChannelMemberRepository,
    ChannelProjectionRepository,
    ChannelProjectionView,
    ChannelRolePolicyProjectionRepository,
    TeamMemberProjectionRepository,
    TeamRoleProjectionRepository,
} from '../repository';

export type ChannelReadAccessRequest = {
    channelId: number;
    userId: number;
};

type AccessMode = 'MESSAGE_READ' | 'CHANNEL_METADATA';

/** 연결된 `Socket`과 `fetchSockets()`가 돌려주는 `RemoteSocket`에 공통인 room 조작 표면. */
export type RoomSocket = {
    data: unknown;
    rooms: Set<string>;
    leave(room: string): unknown;
    emit(event: string, payload: unknown): unknown;
};

const accessKey = (channelId: number, userId: number) => `${channelId}:${userId}`;
const teamUserKey = (teamId: number, userId: number) => `${teamId}:${userId}`;
const policyKey = (channelId: number, roleId: number) => `${channelId}:${roleId}`;

/**
 * Role×channel `message_read` 정책의 단일 평가 지점.
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
        const result = await this.evaluateMany(uniqueChannelIds.map(channelId => ({ channelId, userId })));
        return uniqueChannelIds.filter(channelId => result.get(accessKey(channelId, userId)) === true);
    }

    async filterVisibleChannelIds(_teamId: number, userId: number, channelIds: number[]): Promise<number[]> {
        const uniqueChannelIds = [...new Set(channelIds)];
        const result = await this.evaluateVisibilityMany(
            uniqueChannelIds.map(channelId => ({ channelId, userId })),
        );
        return uniqueChannelIds.filter(channelId => result.get(accessKey(channelId, userId)) === true);
    }

    async findReadableTeamChannelIds(teamId: number, userId: number): Promise<number[]> {
        const channelIds = await this.channelRepository.findIdsByTeamId(teamId);
        return this.filterReadableChannelIds(teamId, userId, channelIds);
    }

    async findReadableProjectChannelIds(projectId: number, userId: number): Promise<number[]> {
        const channelIds = await this.channelRepository.findIdsByProjectId(projectId);
        return this.filterReadableChannelIds(0, userId, channelIds);
    }

    /**
     * `NotificationOutboxPoller`(푸시 알림 수신자 산정), `ChatService.saveSystemMessage`(미읽음
     * 카운터 증가 대상)에서도 쓰이므로 캐시를 타지 않는 {@link evaluateMany}를 그대로 사용한다.
     * 브로드캐스트 전용 캐시가 필요하면 `emitToReadableChannelUsers`를 사용한다.
     */
    async filterReadableUsersByChannel(
        usersByChannel: Map<number, number[]>,
    ): Promise<Map<number, number[]>> {
        const requests = [...usersByChannel].flatMap(([channelId, userIds]) =>
            [...new Set(userIds)].map(userId => ({ channelId, userId })));
        const result = await this.evaluateMany(requests);
        return new Map([...usersByChannel].map(([channelId, userIds]) => [
            channelId,
            [...new Set(userIds)].filter(userId => result.get(accessKey(channelId, userId)) === true),
        ]));
    }

    /**
     * {@link emitToReadableChannelUsers} 전용 (channelId, userId) 단위 캐시 조회.
     * 캐시 히트 항목은 MongoDB를 왕복하지 않고, 미스만 모아 {@link evaluateMany} 한 번으로 채운다.
     * `projectionReadiness`가 준비되지 않은 동안의 결과는 캐시에 쓰지 않는다 — 기동 직후·projection
     * catch-up 중의 "전부 거부" 판정이 최대 TTL만큼 굳어 수신자 복구가 늦어지는 것을 막기 위함이다.
     */

    /**
     * 현재 연결된 socket 중 회수된 message_read 권한을 더 이상 갖지 않는 socket을 room에서 제거한다.
     * readiness가 닫힌 동안의 판정은 전부 거부이므로 판정 전후로 확인하고, 건너뛴 회수는 로컬 room 재검증이 정리한다.
     */
    async evictUnauthorizedSockets(
        io: Server,
        channelIds: number[],
        userIds?: number[],
    ): Promise<void> {
        const uniqueChannelIds = [...new Set(channelIds)];
        if (uniqueChannelIds.length === 0 || !this.projectionReadiness.isReady()) {
            return;
        }

        const rooms = uniqueChannelIds.map(channelId => `chat:${channelId}`);
        const sockets = await io.in(rooms).fetchSockets();
        const affectedUsers = userIds ? new Set(userIds) : undefined;
        const socketsWithChannels = sockets.flatMap(socket => {
            const userId = this.socketUserId(socket);
            if (userId === null || (affectedUsers && !affectedUsers.has(userId))) {
                return [];
            }

            return uniqueChannelIds
                .filter(channelId => socket.rooms.has(`chat:${channelId}`))
                .map(channelId => ({ socket, channelId, userId }));
        });
        const access = await this.evaluateMany(
            socketsWithChannels.map(({ channelId, userId }) => ({ channelId, userId })),
        );
        if (!this.projectionReadiness.isReady()) {
            return;
        }

        for (const { socket, channelId, userId } of socketsWithChannels) {
            if (access.get(accessKey(channelId, userId)) === true) {
                continue;
            }

            socket.leave(`chat:${channelId}`);
            socket.emit('channel:access:revoked', { channelId });
        }
    }

    /**
     * Socket이 가입한 채널·팀 room을 현재 권한으로 다시 검증해 권한이 없는 room에서 제거하고, 제거한 room 수를 반환한다.
     * connection state recovery로 복원된 room과 회수 이벤트의 room 해제가 누락된 room을 정리한다.
     * readiness가 닫힌 동안의 판정은 전부 거부이므로 판정 전후로 확인해, 닫혀 있으면 아무것도 제거하지 않는다.
     */
    async evictUnauthorizedRooms(sockets: RoomSocket[]): Promise<number> {
        if (!this.projectionReadiness.isReady()) {
            return 0;
        }

        const channelRooms = this.joinedRooms(sockets, 'chat:');
        const teamRooms = this.joinedRooms(sockets, 'team:');
        const [access, teamMembers] = await Promise.all([
            this.evaluateMany(channelRooms.map(({ id, userId }) => ({ channelId: id, userId }))),
            this.teamMemberRepository.findByTeamIdsAndUserIds(
                [...new Set(teamRooms.map(({ id }) => id))],
                [...new Set(teamRooms.map(({ userId }) => userId))],
            ),
        ]);
        if (!this.projectionReadiness.isReady()) {
            return 0;
        }

        const activeTeamUsers = new Set(teamMembers.map(member => teamUserKey(member.teamId, member.userId)));
        const deniedChannelRooms = channelRooms.filter(({ id, userId }) => access.get(accessKey(id, userId)) !== true);
        const deniedTeamRooms = teamRooms.filter(({ id, userId }) => !activeTeamUsers.has(teamUserKey(id, userId)));
        for (const { socket, id } of deniedChannelRooms) {
            socket.leave(`chat:${id}`);
            socket.emit('channel:access:revoked', { channelId: id });
        }

        for (const { socket, id } of deniedTeamRooms) {
            socket.leave(`team:${id}`);
            socket.emit('team:access:revoked', { teamId: id });
        }

        return deniedChannelRooms.length + deniedTeamRooms.length;
    }

    private joinedRooms(sockets: RoomSocket[], prefix: 'chat:' | 'team:') {
        return sockets.flatMap(socket => {
            const userId = this.socketUserId(socket);
            if (userId === null) {
                return [];
            }

            return [...socket.rooms]
                .filter(room => room.startsWith(prefix))
                .map(room => ({ socket, id: Number(room.slice(prefix.length)), userId }));
        });
    }

    /**
     * `chat:{channelId}` room에 그대로 브로드캐스트한다.
     *
     * room 멤버십 자체가 `message_read` 판정 결과라는 불변식에 기댄다 — 입장할 때
     * {@link canReadChannel}로 판정하고, 권한이 회수되면 {@link evictUnauthorizedSockets}가
     * room에서 제거한다. 그래서 메시지마다 다시 평가하지 않는다.
     *
     * 이전에는 `fetchSockets()`로 room의 소켓을 모아 판정한 뒤 소켓 ID 목록을 대상으로 지정했다.
     * Redis adapter 환경에서 그 호출은 모든 인스턴스의 응답을 기다리므로 접속자 수에 비례해
     * 비용이 늘고, 메시지 1건의 처리 시간을 그만큼 점유했다.
     *
     * 이벤트 유실이나 projection 재구축으로 room 멤버십이 실제 권한과 어긋날 수 있으므로,
     * `ChatGateway`의 주기적인 로컬 room sweep(`evictUnauthorizedRooms`)이 대조해 복구한다.
     */
    async emitToReadableChannelUsers(
        io: Server | undefined,
        channelId: number,
        event: string,
        payload: unknown,
        excludedSocketId?: string,
    ): Promise<void> {
        if (!io) {
            return;
        }

        // `chat:{channelId}` room의 멤버십 자체가 message_read 판정 결과다. 입장할 때 판정하고
        // 권한 회수 시 room에서 제거하므로, 메시지마다 다시 평가하지 않는다. 그 덕분에 Redis adapter
        // 환경에서도 소켓 목록을 묻는 인스턴스 간 왕복이 생기지 않는다.
        const target = excludedSocketId
            ? io.to(`chat:${channelId}`).except(excludedSocketId)
            : io.to(`chat:${channelId}`);
        target.emit(event, payload);
    }

    async emitChannelEventToVisibleTeamUsers(
        io: Server,
        teamId: number,
        channelId: number,
        event: string,
        payload: unknown,
    ): Promise<void> {
        const socketIds = await this.captureVisibleTeamSocketIds(io, teamId, channelId);
        for (const socketId of socketIds) {
            io.to(socketId).emit(event, payload);
        }
    }

    /** Stale team room 구독자를 제외하고 현재 active 팀 멤버에게만 팀 범위 metadata를 전송한다. */
    async emitToActiveTeamUsers(
        io: Server,
        teamId: number,
        event: string,
        payload: unknown,
    ): Promise<void> {
        if (!this.projectionReadiness.isReady()) {
            return;
        }

        const sockets = await io.in(`team:${teamId}`).fetchSockets();
        const userIds = [...new Set(sockets
            .map(socket => this.socketUserId(socket))
            .filter((userId): userId is number => userId !== null))];
        const members = await this.teamMemberRepository.findByTeamIdsAndUserIds([teamId], userIds);
        const activeUserIds = new Set(members
            .filter(member => member.teamId === teamId)
            .map(member => member.userId));
        for (const socket of sockets) {
            const userId = this.socketUserId(socket);
            if (userId !== null && activeUserIds.has(userId)) {
                socket.emit(event, payload);
            }
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
            .map(socket => this.socketUserId(socket))
            .filter((userId): userId is number => userId !== null);
        const access = await this.evaluateVisibilityMany(
            [...new Set(users)].map(userId => ({ channelId, userId })),
        );
        return sockets
            .filter(socket => {
                const userId = this.socketUserId(socket);
                return userId !== null && access.get(accessKey(channelId, userId)) === true;
            })
            .map(socket => socket.id);
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
            requests.map(request => [accessKey(request.channelId, request.userId), request]),
        ).values()];
        const result = new Map(uniqueRequests.map(request => [accessKey(request.channelId, request.userId), false]));
        if (uniqueRequests.length === 0 || !this.projectionReadiness.isReady()) {
            return result;
        }

        const channelIds = [...new Set(uniqueRequests.map(({ channelId }) => channelId))];
        const userIds = [...new Set(uniqueRequests.map(({ userId }) => userId))];
        const [channels, membersByChannel] = await Promise.all([
            this.channelRepository.findByIds(channelIds),
            this.channelMemberRepository.findByChannelIdsAndUserIds(channelIds, userIds),
        ]);
        const channelById = new Map(channels.map(channel => [channel.channelId, channel]));
        const memberKeys = new Set<string>();
        for (const [channelId, members] of membersByChannel) {
            for (const member of members) {
                const key = accessKey(channelId, member.userId);
                memberKeys.add(key);
            }
        }

        const teamRequests = uniqueRequests.flatMap(request => {
            const channel = channelById.get(request.channelId);
            if (!channel) {
                return [];
            }

            if (this.isDm(channel)) {
                result.set(accessKey(request.channelId, request.userId), memberKeys.has(accessKey(request.channelId, request.userId)));
                return [];
            }

            return channel.teamId === null || channel.type === 'DM' ? [] : [{ request, channel, teamId: channel.teamId }];
        });
        if (teamRequests.length === 0) {
            return result;
        }

        const teamIds = [...new Set(teamRequests.map(({ teamId }) => teamId))];
        const teamMembers = await this.teamMemberRepository.findByTeamIdsAndUserIds(teamIds, userIds);
        const teamMemberByKey = new Map(teamMembers.map(member => [teamUserKey(member.teamId, member.userId), member]));

        const policyCandidates = teamRequests.filter(({ request, channel, teamId }) => {
            const member = teamMemberByKey.get(teamUserKey(teamId, request.userId));
            if (!member) {
                return false;
            }

            const hasChannelMembership = memberKeys.has(accessKey(request.channelId, request.userId));
            if ((mode === 'MESSAGE_READ' && !hasChannelMembership) || (channel.isPrivate && !hasChannelMembership)) {
                return false;
            }

            if (member.role === 'OWNER') {
                result.set(accessKey(request.channelId, request.userId), true);
                return false;
            }

            return true;
        });
        if (policyCandidates.length === 0) {
            return result;
        }

        const accountIds = [...new Set(policyCandidates.map(({ request }) => request.userId))];
        const assignments = await this.teamRoleRepository.findAssignmentsByTeamIdsAndAccountIds(teamIds, accountIds);
        const roleIds = [...new Set(assignments.map(({ roleId }) => roleId))];
        const candidateChannelIds = [...new Set(policyCandidates.map(({ request }) => request.channelId))];
        const [roles, policies] = await Promise.all([
            this.teamRoleRepository.findRolesByIds(roleIds),
            this.policyRepository.findByChannelIdsAndRoleIds(candidateChannelIds, roleIds),
        ]);
        const roleById = new Map(roles.map(role => [role.roleId, role]));
        const policyByKey = new Map(policies.map(policy => [policyKey(policy.channelId, policy.roleId), policy]));
        const assignmentsByTeamUser = new Map<string, number[]>();
        for (const assignment of assignments) {
            const key = teamUserKey(assignment.teamId, assignment.accountId);
            const existing = assignmentsByTeamUser.get(key);
            if (existing) {
                existing.push(assignment.roleId);
            } else {
                assignmentsByTeamUser.set(key, [assignment.roleId]);
            }
        }

        for (const { request, teamId } of policyCandidates) {
            const assignedRoleIds = assignmentsByTeamUser.get(teamUserKey(teamId, request.userId)) ?? [];
            let selectedPriority: number | undefined;
            let selected = false;
            for (const roleId of assignedRoleIds) {
                const role = roleById.get(roleId);
                const policy = policyByKey.get(policyKey(request.channelId, roleId));
                if (role?.teamId !== teamId || policy?.teamId !== teamId) {
                    continue;
                }

                if (selectedPriority === undefined || role.priority > selectedPriority) {
                    selectedPriority = role.priority;
                    selected = policy.messageRead;
                } else if (role.priority === selectedPriority && !policy.messageRead) {
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
        if (typeof socket.data !== 'object' || socket.data === null) {
            return null;
        }

        const rawUserId = (socket.data as Record<string, unknown>).userId;
        const userId = typeof rawUserId === 'number' || typeof rawUserId === 'string'
            ? Number(rawUserId)
            : NaN;
        return Number.isSafeInteger(userId) && userId > 0 ? userId : null;
    }
}
