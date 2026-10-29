import {
	ForbiddenException,
	Injectable as injectable,
	ServiceUnavailableException,
	Inject as inject,
} from '@nestjs/common';
import {Server} from 'socket.io';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {
	DmParticipantRepository,
	ChannelProjectionRepository,
	ChannelProjectionView,
	ChannelRolePolicyProjectionRepository,
	TeamMemberProjectionRepository,
	TeamRoleProjectionRepository,
} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {hasChannelPermission} from './channel-permission.js';

export type ChannelReadAccessRequest = {
	channelId: number;
	userId: number;
};

export type ChannelActionPermission = 'message_send' | 'thread_reply' | 'file_attach' | 'reaction_add' | 'message_pin' | 'message_manage' | 'slash_command_use' | 'github_issue_create';
type AccessMode = 'MESSAGE_READ' | 'CHANNEL_METADATA' | ChannelActionPermission;

/**
연결된 `Socket`과 `fetchSockets()`가 돌려주는 `RemoteSocket`에 공통인 room 조작 표면.
*/
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
 Role×channel 읽기 및 채팅 작업 권한의 단일 평가 지점.

 기본·개별 역할 권한을 합친 뒤 기본 역할 overwrite, 개별 역할 deny, allow 순서로 적용한다.
 활성 팀 멤버의 built-in OWNER와 ADMIN은 허용한다.
 팀 채널 접근은 역할 권한으로 결정하고 DM만 대화 참여자를 확인한다.
 */
@injectable()
export class ChannelMessageReadAccessService {
	/**
    준비 상태(readiness)가 닫혀 브로드캐스트를 건너뛴 적이 있어, 다시 열린 뒤 첫 전송 전에 로컬 room 재검증이 필요한지.
    */
	private needsRoomSweepAfterReadinessGap = false;
	private roomSweepAfterReadinessGap?: Promise<void>;

	@inject()
	private readonly channelRepository!: ChannelProjectionRepository;

	@inject()
	private readonly dmParticipantRepository!: DmParticipantRepository;

	@inject()
	private readonly teamMemberRepository!: TeamMemberProjectionRepository;

	@inject()
	private readonly teamRoleRepository!: TeamRoleProjectionRepository;

	@inject()
	private readonly policyRepository!: ChannelRolePolicyProjectionRepository;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	private joinedRooms(sockets: RoomSocket[], prefix: 'chat:' | 'team:') {
		return sockets.flatMap(socket => {
			const userId = this.socketUserId(socket);
			if (userId === null) {
				return [];
			}

			return [...socket.rooms]
				.filter(room => room.startsWith(prefix))
				.map(room => ({socket, id: Number(room.slice(prefix.length)), userId}));
		});
	}

	/**
     준비 상태(readiness)가 닫혔다 열린 뒤 첫 전송 전에 이 인스턴스의 room을 한 번 재검증한다.
     동시에 들어온 전송들은 같은 재검증을 기다린다. 재검증 도중 readiness가 다시 닫히면 false를 돌려준다.
     */
	private async ensureRoomsSweptAfterReadinessGap(io: Server): Promise<boolean> {
		if (!this.needsRoomSweepAfterReadinessGap) {
			return true;
		}

		this.roomSweepAfterReadinessGap ??= io.local.fetchSockets()
			.then(async sockets => this.evictUnauthorizedRooms(sockets))
			.then(() => undefined);

		const sweep = this.roomSweepAfterReadinessGap;
		try {
			await sweep;
		} finally {
			if (this.roomSweepAfterReadinessGap === sweep) {
				this.roomSweepAfterReadinessGap = undefined;
			}
		}

		if (!this.projectionReadiness.isReady()) {
			return false;
		}

		this.needsRoomSweepAfterReadinessGap = false;
		return true;
	}

	private async evaluate(
		requests: ChannelReadAccessRequest[],
		modes: AccessMode[],
	): Promise<Map<string, boolean>> {
		const uniqueRequests = new Map(
			requests.map(request => [accessKey(request.channelId, request.userId), request]),
		).values().toArray();
		const result = new Map(uniqueRequests.map(request => [accessKey(request.channelId, request.userId), false]));
		if (uniqueRequests.length === 0 || !this.projectionReadiness.isReady()) {
			return result;
		}

		const channelIds = [...new Set(uniqueRequests.map(({channelId}) => channelId))];
		const userIds = [...new Set(uniqueRequests.map(({userId}) => userId))];
		const [channels, membersByChannel] = await Promise.all([
			this.channelRepository.findByIds(channelIds),
			this.dmParticipantRepository.findByChannelIdsAndUserIds(channelIds, userIds),
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
				result.set(accessKey(request.channelId, request.userId),
					memberKeys.has(accessKey(request.channelId, request.userId)) && !modes.includes('message_manage'));
				return [];
			}

			return channel.teamId === null || channel.type === 'DM' ? [] : [{request, channel, teamId: channel.teamId}];
		});
		if (teamRequests.length === 0) {
			return result;
		}

		const teamIds = [...new Set(teamRequests.map(({teamId}) => teamId))];
		const teamMembers = await this.teamMemberRepository.findByTeamIdsAndUserIds(teamIds, userIds);
		const teamMemberByKey = new Map(teamMembers.map(member => [teamUserKey(member.teamId, member.userId), member]));

		const policyCandidates = teamRequests.filter(({request, teamId}) => {
			const member = teamMemberByKey.get(teamUserKey(teamId, request.userId));
			if (!member) {
				return false;
			}

			if (member.role === 'OWNER' || member.role === 'ADMIN') {
				result.set(accessKey(request.channelId, request.userId), true);
				return false;
			}

			return true;
		});
		if (policyCandidates.length === 0) {
			return result;
		}

		const accountIds = [...new Set(policyCandidates.map(({request}) => request.userId))];
		const [assignments, defaults] = await Promise.all([
			this.teamRoleRepository.findAssignmentsByTeamIdsAndAccountIds(teamIds, accountIds),
			this.teamRoleRepository.findDefaultRolesByTeamIds(teamIds),
		]);
		const roleIds = [...new Set([...assignments, ...defaults].map(({roleId}) => roleId))];
		const candidateChannelIds = [...new Set(policyCandidates.map(({request}) => request.channelId))];
		const [roles, policies] = await Promise.all([
			this.teamRoleRepository.findRolesByIds(roleIds),
			this.policyRepository.findByChannelIdsAndRoleIds(candidateChannelIds, roleIds),
		]);
		const roleById = new Map([...roles, ...defaults].map(role => [role.roleId, role]));
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

		for (const {request, channel, teamId} of policyCandidates) {
			const assignedRoleIds = assignmentsByTeamUser.get(teamUserKey(teamId, request.userId)) ?? [];
			const applicable = roleById.values().filter(role => role.teamId === teamId
				&& (role.isDefault === true || assignedRoleIds.includes(role.roleId))).toArray();
			const channelPolicies = new Map(applicable.flatMap(role => {
				const policy = policyByKey.get(policyKey(request.channelId, role.roleId));
				return policy?.teamId === teamId ? [[role.roleId, policy.permissions ?? {}] as const] : [];
			}));
			const visibilityPermission = channel.type === 'VOICE' ? 'voice_connect' : 'message_read';
			const canUse = (permission: string) => hasChannelPermission(
				applicable, channelPolicies, permission, (channel.isPrivate ?? true) && (permission === 'message_read' || permission === 'voice_connect'),
			);
			const isAllowed = canUse(visibilityPermission) && modes.every(mode => canUse(
				mode === 'CHANNEL_METADATA' ? visibilityPermission : (mode === 'MESSAGE_READ' ? 'message_read' : mode),
			));
			result.set(accessKey(request.channelId, request.userId), isAllowed);
		}

		return result;
	}

	private isDm(channel: ChannelProjectionView): boolean {
		return channel.teamId === null && channel.type === 'DM';
	}

	private socketUserId(socket: {data: unknown}): Nullable<number> {
		if (typeof socket.data !== 'object' || socket.data === null) {
			return null;
		}

		const rawUserId = (socket.data as Record<string, unknown>).userId;
		const userId = typeof rawUserId === 'number' || typeof rawUserId === 'string'
			? Number(rawUserId)
			: NaN;
		return Number.isSafeInteger(userId) && userId > 0 ? userId : null;
	}

	async canReadChannel(channelId: number, userId: number): Promise<boolean> {
		const result = await this.evaluateMany([{channelId, userId}]);
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

	async requirePermission(channelId: number, userId: number, permission: ChannelActionPermission): Promise<void> {
		await this.requirePermissions(channelId, userId, [permission]);
	}

	async requirePermissions(channelId: number, userId: number, permissions: ChannelActionPermission[]): Promise<void> {
		if (!this.projectionReadiness.isReady()) {
			throw new ServiceUnavailableException('권한 정보가 아직 동기화되지 않았습니다');
		}

		const result = await this.evaluate([{channelId, userId}], ['MESSAGE_READ', ...new Set(permissions)]);
		if (result.get(accessKey(channelId, userId)) !== true) {
			throw new ForbiddenException('채널 메시지 읽기 또는 요청한 작업 권한이 없습니다');
		}
	}

	async filterReadableChannelIds(_teamId: number, userId: number, channelIds: number[]): Promise<number[]> {
		const uniqueChannelIds = [...new Set(channelIds)];
		const result = await this.evaluateMany(uniqueChannelIds.map(channelId => ({channelId, userId})));
		return uniqueChannelIds.filter(channelId => result.get(accessKey(channelId, userId)) === true);
	}

	async filterVisibleChannelIds(_teamId: number, userId: number, channelIds: number[]): Promise<number[]> {
		const uniqueChannelIds = [...new Set(channelIds)];
		const result = await this.evaluateVisibilityMany(
			uniqueChannelIds.map(channelId => ({channelId, userId})),
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
     `NotificationOutboxPoller`(푸시 알림 수신자 산정), `ChatService.saveSystemMessage`(미읽음
     카운터 증가 대상)에서도 쓰이므로 캐시를 타지 않는 {@link evaluateMany}를 그대로 사용한다.
     */
	async findReadableUsersByChannel(channelIds: number[]): Promise<Map<number, number[]>> {
		const channels = await this.channelRepository.findByIds(channelIds);
		const teamIds = [...new Set(channels.flatMap(channel => channel.teamId === null ? [] : [channel.teamId]))];
		const [teamMembers, dmParticipants] = await Promise.all([
			this.teamMemberRepository.findByTeamIds(teamIds),
			this.dmParticipantRepository.findByChannelIds(channels.filter(channel => this.isDm(channel)).map(channel => channel.channelId)),
		]);
		const usersByTeam = new Map<number, number[]>();
		for (const member of teamMembers) {
			const users = usersByTeam.get(member.teamId) ?? [];
			users.push(member.userId);
			usersByTeam.set(member.teamId, users);
		}

		return this.filterReadableUsersByChannel(new Map(channels.map(channel => [
			channel.channelId,
			channel.teamId === null
				? (dmParticipants.get(channel.channelId) ?? []).map(participant => participant.userId)
				: usersByTeam.get(channel.teamId) ?? [],
		])));
	}

	async filterReadableUsersByChannel(
		usersByChannel: Map<number, number[]>,
	): Promise<Map<number, number[]>> {
		const requests = [...usersByChannel].flatMap(([channelId, userIds]) =>
			[...new Set(userIds)].map(userId => ({channelId, userId})));
		const result = await this.evaluateMany(requests);
		return new Map([...usersByChannel].map(([channelId, userIds]) => [
			channelId,
			[...new Set(userIds)].filter(userId => result.get(accessKey(channelId, userId)) === true),
		]));
	}

	/**
     현재 연결된 socket 중 회수된 message_read 권한을 더 이상 갖지 않는 socket을 room에서 제거한다.
     readiness가 닫힌 동안의 판정은 전부 거부이므로 판정 전후로 확인하고, 건너뛴 회수는 로컬 room 재검증이 정리한다.
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
				.map(channelId => ({socket, channelId, userId}));
		});
		const access = await this.evaluateMany(
			socketsWithChannels.map(({channelId, userId}) => ({channelId, userId})),
		);
		if (!this.projectionReadiness.isReady()) {
			return;
		}

		for (const {socket, channelId, userId} of socketsWithChannels) {
			if (access.get(accessKey(channelId, userId)) === true) {
				continue;
			}

			socket.leave(`chat:${channelId}`);
			socket.emit('channel:access:revoked', {channelId});
		}
	}

	/**
     Socket이 가입한 채널·팀 room을 현재 권한으로 다시 검증해 권한이 없는 room에서 제거하고, 제거한 room 수를 반환한다.
     connection state recovery로 복원된 room과 회수 이벤트의 room 해제가 누락된 room을 정리한다.
     readiness가 닫힌 동안의 판정은 전부 거부이므로 판정 전후로 확인해, 닫혀 있으면 아무것도 제거하지 않는다.
     */
	async evictUnauthorizedRooms(sockets: RoomSocket[]): Promise<number> {
		if (!this.projectionReadiness.isReady()) {
			return 0;
		}

		const channelRooms = this.joinedRooms(sockets, 'chat:');
		const teamRooms = this.joinedRooms(sockets, 'team:');
		const teamIds = [...new Set(teamRooms.map(({id}) => id))];
		const teamUserIds = [...new Set(teamRooms.map(({userId}) => userId))];
		const [access, teamMembers] = await Promise.all([
			this.evaluateMany(channelRooms.map(({id, userId}) => ({channelId: id, userId}))),
			this.teamMemberRepository.findByTeamIdsAndUserIds(teamIds, teamUserIds),
		]);
		if (!this.projectionReadiness.isReady()) {
			return 0;
		}

		const activeTeamUsers = new Set(teamMembers.map(member => teamUserKey(member.teamId, member.userId)));
		const deniedChannelRooms = channelRooms.filter(({id, userId}) => access.get(accessKey(id, userId)) !== true);
		const deniedTeamRooms = teamRooms.filter(({id, userId}) => !activeTeamUsers.has(teamUserKey(id, userId)));
		for (const {socket, id} of deniedChannelRooms) {
			socket.leave(`chat:${id}`);
			socket.emit('channel:access:revoked', {channelId: id});
		}

		for (const {socket, id} of deniedTeamRooms) {
			socket.leave(`team:${id}`);
			socket.emit('team:access:revoked', {teamId: id});
		}

		return deniedChannelRooms.length + deniedTeamRooms.length;
	}

	/**
     `chat:{channelId}` room에 그대로 브로드캐스트한다.

     room 멤버십 자체가 `message_read` 판정 결과라는 불변식에 기댄다 — 입장할 때
     {@link canReadChannel}로 판정하고, 권한이 회수되면 {@link evictUnauthorizedSockets}가
     room에서 제거한다. 그래서 메시지마다 다시 평가하지 않는다.

     이전에는 `fetchSockets()`로 room의 소켓을 모아 판정한 뒤 소켓 ID 목록을 대상으로 지정했다.
     Redis adapter 환경에서 그 호출은 모든 인스턴스의 응답을 기다리므로 접속자 수에 비례해
     비용이 늘고, 메시지 1건의 처리 시간을 그만큼 점유했다.

     projection readiness가 닫혀 있는 동안은 전송하지 않는다(fail-closed). 다시 열린 뒤 첫 전송 전에는
     로컬 room을 한 번 재검증해, 닫혀 있는 동안 버려진 회수가 room 멤버십으로 남지 않게 한다.

     이벤트 유실이나 projection 재구축으로 room 멤버십이 실제 권한과 어긋날 수 있으므로,
     `ChatGateway`의 주기적인 로컬 room sweep(`evictUnauthorizedRooms`)이 대조해 복구한다.
     */
	async emitToReadableChannelUsers(
		io: Server | undefined,
		channelId: number,
		message: {
			event: string;
			payload: unknown;
			excludedSocketId?: string;
		},

	): Promise<void> {
		const {event, payload, excludedSocketId} = message;
		if (!io) {
			return;
		}

		// 채팅방(room) 멤버십은 projection 기준의 판정 결과이므로, projection이 catch-up 중이면 그 판정을 믿을 수 없다.
		// 이전에는 이 구간의 평가가 전부 거부로 떨어져 아무에게도 전달되지 않았다(fail-closed). 같은 성질을 유지한다.
		if (!this.projectionReadiness.isReady()) {
			this.needsRoomSweepAfterReadinessGap = true;
			return;
		}

		// 닫혀 있던 동안 처리된 회수는 evict 쪽에서 버려졌다. 다시 열린 뒤 첫 전송 전에 로컬 room을 대조해,
		// 주기 sweep(최대 30초)을 기다리는 동안 회수된 사용자가 메시지를 받지 않게 한다.
		if (!(await this.ensureRoomsSweptAfterReadinessGap(io))) {
			return;
		}

		// `chat:{channelId}` room의 멤버십 자체가 message_read 판정 결과다. 입장할 때 판정하고
		// 권한 회수 시 room에서 제거하므로, 메시지마다 다시 평가하지 않는다. 그 덕분에 Redis adapter
		// 환경에서도 소켓 목록을 묻는 인스턴스 간 왕복이 생기지 않는다.
		const target = excludedSocketId === undefined
			? io.to(`chat:${channelId}`)
			: io.to(`chat:${channelId}`).except(excludedSocketId);
		target.emit(event, payload);
	}

	async emitChannelEventToVisibleTeamUsers(
		io: Server,
		teamId: number,
		channelId: number,
		message: {
			event: string;
			payload: unknown;
		},

	): Promise<void> {
		const {event, payload} = message;
		const socketIds = await this.captureVisibleTeamSocketIds(io, teamId, channelId);
		for (const socketId of socketIds) {
			io.to(socketId).emit(event, payload);
		}
	}

	/**
    Stale team room 구독자를 제외하고 현재 active 팀 멤버에게만 팀 범위 metadata를 전송한다.
    */
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

	/**
    삭제 전 projection으로 대상 socket을 캡처해 삭제 후에도 비공개 metadata 수신자를 제한한다.
    */
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
			[...new Set(users)].map(userId => ({channelId, userId})),
		);
		return sockets
			.filter(socket => {
				const userId = this.socketUserId(socket);
				return userId !== null && access.get(accessKey(channelId, userId)) === true;
			})
			.map(socket => socket.id);
	}

	async evaluateMany(requests: ChannelReadAccessRequest[]): Promise<Map<string, boolean>> {
		return this.evaluate(requests, ['MESSAGE_READ']);
	}

	async evaluateVisibilityMany(requests: ChannelReadAccessRequest[]): Promise<Map<string, boolean>> {
		return this.evaluate(requests, ['CHANNEL_METADATA']);
	}
}
