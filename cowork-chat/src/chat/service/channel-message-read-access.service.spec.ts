import {ForbiddenException, ServiceUnavailableException} from '@nestjs/common';
import {type Nullable} from '../../common/type/nullable.js';
import {ChannelMessageReadAccessService, isChannelAccessAllowed} from './channel-message-read-access.service.js';

type Decision = {
	channelId: number;
	userId: number;
	teamId: number;
	allowedPermissions: string[];
	readPrivate: boolean;
	sourceAuthorizationVersion: Nullable<bigint>;
};

const member = (userId: number, authorizationVersion = 1n) => ({
	teamId: 1, userId, role: 'MEMBER', teamName: 'team', authorizationVersion,
});
const decision = (channelId: number, userId: number, allowedPermissions: string[], sourceAuthorizationVersion: Nullable<bigint> = 1n): Decision => ({
	channelId, userId, teamId: 1, allowedPermissions, readPrivate: false, sourceAuthorizationVersion,
});
const privateDecision = (channelId: number, userId: number, allowedPermissions: string[]): Decision => ({
	...decision(channelId, userId, allowedPermissions), readPrivate: true,
});

describe('isChannelAccessAllowed', () => {
	const current = member(2);

	it('텍스트 채널은 message_read, 음성 채널은 voice_connect를 접근 권한으로 쓴다', () => {
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read']), current, {type: 'TEXT', isPrivate: false}, ['CHANNEL_METADATA'])).toBe(true);
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read']), current, {type: 'VOICE', isPrivate: false}, ['CHANNEL_METADATA'])).toBe(false);
		expect(isChannelAccessAllowed(decision(10, 2, ['voice_connect']), current, {type: 'VOICE', isPrivate: false}, ['CHANNEL_METADATA'])).toBe(true);
		expect(isChannelAccessAllowed(decision(10, 2, ['voice_connect']), current, {type: 'VOICE', isPrivate: false}, ['MESSAGE_READ'])).toBe(false);
	});

	it('작업 권한은 접근 권한과 함께 허용되어야 한다', () => {
		expect(isChannelAccessAllowed(decision(10, 2, ['message_send']), current, {type: 'TEXT', isPrivate: false}, ['message_send'])).toBe(false);
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read']), current, {type: 'TEXT', isPrivate: false}, ['message_send'])).toBe(false);
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read', 'message_send', 'file_attach']), current, {type: 'TEXT', isPrivate: false}, ['message_send', 'file_attach'])).toBe(true);
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read', 'message_send']), current, {type: 'TEXT', isPrivate: false}, ['message_send', 'file_attach'])).toBe(false);
	});

	it('voice_log_read는 음성 채널에서만 요구한다', () => {
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read']), current, {type: 'TEXT', isPrivate: false}, ['MESSAGE_READ', 'VOICE_LOG_READ'])).toBe(true);
		expect(isChannelAccessAllowed(decision(30, 2, ['voice_connect', 'message_read']), current, {type: 'VOICE', isPrivate: false}, ['MESSAGE_READ', 'VOICE_LOG_READ'])).toBe(false);
		expect(isChannelAccessAllowed(decision(30, 2, ['voice_connect', 'message_read', 'voice_log_read']), current, {type: 'VOICE', isPrivate: false}, ['MESSAGE_READ', 'VOICE_LOG_READ'])).toBe(true);
	});

	it('비공개이거나 비공개 여부를 모르는 채널은 공개 기준 판정 대신 비공개 접근 값을 요구한다', () => {
		for (const isPrivate of [true, undefined]) {
			expect(isChannelAccessAllowed(decision(10, 2, ['message_read']), current, {type: 'TEXT', isPrivate}, ['MESSAGE_READ'])).toBe(false);
			expect(isChannelAccessAllowed(privateDecision(10, 2, ['message_read']), current, {type: 'TEXT', isPrivate}, ['MESSAGE_READ'])).toBe(true);
		}
	});

	it('판정 결과나 활성 멤버십이 없으면 거부한다', () => {
		expect(isChannelAccessAllowed(undefined, current, {type: 'TEXT', isPrivate: false}, ['MESSAGE_READ'])).toBe(false);
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read']), undefined, {type: 'TEXT', isPrivate: false}, ['MESSAGE_READ'])).toBe(false);
	});

	it('현재 인가 version이나 팀과 다르게 계산된 판정 결과는 Preference가 다시 발행할 때까지 거부한다', () => {
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read'], 2n), current, {type: 'TEXT', isPrivate: false}, ['MESSAGE_READ'])).toBe(false);
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read'], null), current, {type: 'TEXT', isPrivate: false}, ['MESSAGE_READ'])).toBe(false);
		expect(isChannelAccessAllowed({...decision(10, 2, ['message_read']), teamId: 2}, current, {type: 'TEXT', isPrivate: false}, ['MESSAGE_READ'])).toBe(false);
		expect(isChannelAccessAllowed(decision(10, 2, ['message_read'], 0n), member(2, 0n), {type: 'TEXT', isPrivate: false}, ['MESSAGE_READ'])).toBe(false);
	});
});

describe('ChannelMessageReadAccessService', () => {
	const channelRepository = {
		findById: jest.fn(), findByIds: jest.fn(), findIdsByTeamId: jest.fn(), findIdsByProjectId: jest.fn(),
	};
	const dmParticipantRepository = {findByChannelIdsAndUserIds: jest.fn(), findByChannelIds: jest.fn()};
	const teamMemberRepository = {findMembership: jest.fn(), findByTeamIdsAndUserIds: jest.fn(), findByTeamIds: jest.fn()};
	const channelAccessRepository = {findByChannelIdsAndUserIds: jest.fn()};
	const projectionReadiness = {areReady: jest.fn()};
	// 브로드캐스트 판정 캐시가 인스턴스에 상태로 남으므로, 테스트 간 오염을 막기 위해
	// 매 테스트마다 새 인스턴스를 만든다(캐시도 함께 초기화됨).
	let service: ChannelMessageReadAccessService;

	const setDecisions = (decisions: Decision[]) => {
		channelAccessRepository.findByChannelIdsAndUserIds.mockImplementation(async (channelIds: number[], userIds: number[]) => (
			decisions.filter(item => channelIds.includes(item.channelId) && userIds.includes(item.userId))
		));
	};

	it('derives notification recipients from team permissions without channel member rows', async () => {
		dmParticipantRepository.findByChannelIds.mockResolvedValue(new Map());
		teamMemberRepository.findByTeamIds.mockResolvedValue([member(2), member(4)]);
		const result = await service.findReadableUsersByChannel([10, 20]);
		expect(result.get(10)).toEqual([2]);
		expect(result.get(20)).toEqual([2]);
		expect(dmParticipantRepository.findByChannelIds).toHaveBeenCalledWith([]);
	});

	beforeEach(() => {
		jest.clearAllMocks();
		service = Object.assign<ChannelMessageReadAccessService, Record<string, unknown>>(new ChannelMessageReadAccessService(), {
			channelRepository,
			dmParticipantRepository,
			teamMemberRepository,
			channelAccessRepository,
			projectionReadiness,
		});
		projectionReadiness.areReady.mockReturnValue(true);
		channelRepository.findByIds.mockResolvedValue([
			{
				channelId: 10, teamId: 1, type: 'TEXT', isPrivate: false,
			},
			{
				channelId: 20, teamId: 1, type: 'TEXT', isPrivate: true,
			},
		]);
		dmParticipantRepository.findByChannelIdsAndUserIds.mockResolvedValue(new Map([
			[10, [1, 2, 3, 4, 5, 6].map(userId => ({
				channelId: 10,
				userId,
				teamId: 1,
				channelType: 'TEXT',
			}))],
			[20, [{
				channelId: 20, userId: 5, teamId: 1, channelType: 'TEXT',
			}]],
		]));
		teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([1, 2, 3, 4, 5, 6, 7].map(userId => member(userId)));
		// 사용자 4는 판정 결과가 없고, 1과 7은 모든 작업을 허용받는다.
		const everything = ['message_read', 'message_send', 'thread_reply', 'file_attach', 'message_manage'];
		setDecisions([
			decision(10, 1, everything),
			privateDecision(20, 1, everything),
			decision(10, 7, everything),
			decision(10, 2, ['message_read']),
			privateDecision(20, 2, ['message_read']),
			decision(10, 3, ['message_read']),
			decision(20, 3, []),
			decision(10, 5, ['message_read']),
			privateDecision(20, 5, ['message_read']),
			decision(10, 6, ['message_read']),
		]);
	});

	it('DM 읽음 처리에는 팀 가입 시각을 적용하지 않는다', async () => {
		channelRepository.findById.mockResolvedValue({type: 'DM', teamId: null});
		await expect(service.requireChannelJoinedAt(10, 2)).resolves.toBeUndefined();
		expect(teamMemberRepository.findMembership).not.toHaveBeenCalled();
	});

	it('팀원이 아니면 접근을 거부하고 가입 시각 동기화 전에는 집계를 보류한다', async () => {
		teamMemberRepository.findMembership.mockResolvedValue(null);
		await expect(service.requireTeamJoinedAt(1, 2)).rejects.toThrow(ForbiddenException);

		teamMemberRepository.findMembership.mockResolvedValue({});
		await expect(service.requireTeamJoinedAt(1, 2)).rejects.toThrow(ServiceUnavailableException);

		const joinedAt = new Date('2026-11-25T10:00:00Z');
		teamMemberRepository.findMembership.mockResolvedValue({joinedAt});
		await expect(service.requireTeamJoinedAt(1, 2)).resolves.toEqual(joinedAt);
	});

	describe('판정 결과 조회', () => {
		it('판정 결과가 허용한 채널만 읽는다', async () => {
			await expect(service.canReadChannel(10, 2)).resolves.toBe(true);
			await expect(service.canReadChannel(20, 3)).resolves.toBe(false);
			await expect(service.canReadChannel(10, 4)).resolves.toBe(false);
			await expect(service.filterVisibleChannelIds(3, [10, 20])).resolves.toEqual([10]);
		});

		it('활성 팀 멤버가 아니면 판정 결과가 허용해도 거부한다', async () => {
			teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([]);
			await expect(service.canReadChannel(10, 1)).resolves.toBe(false);
			await expect(service.requirePermission(10, 1, 'message_manage')).rejects.toThrow(ForbiddenException);
		});

		it('멤버십 변경 뒤 다시 발행되지 않은 판정 결과는 쓰지 않는다', async () => {
			teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([member(2, 2n)]);
			await expect(service.canReadChannel(10, 2)).resolves.toBe(false);
		});

		it('작업 권한은 판정 결과의 해당 키를 요구한다', async () => {
			await expect(service.requirePermission(10, 1, 'message_manage')).resolves.toBeUndefined();
			await expect(service.requirePermission(10, 2, 'message_send')).rejects.toThrow(ForbiddenException);
		});

		it('여러 작업 권한을 판정 결과 한 번의 조회로 확인한다', async () => {
			await expect(service.requirePermissions(20, 1, ['message_send', 'thread_reply', 'file_attach']))
				.resolves.toBeUndefined();
			expect(channelRepository.findByIds).toHaveBeenCalledTimes(1);
			expect(teamMemberRepository.findByTeamIdsAndUserIds).toHaveBeenCalledTimes(1);
			expect(channelAccessRepository.findByChannelIdsAndUserIds).toHaveBeenCalledTimes(1);
		});

		it('DM 참여자도 서로의 메시지를 관리할 수 없다', async () => {
			channelRepository.findByIds.mockResolvedValue([{channelId: 10, teamId: null, type: 'DM'}]);
			await expect(service.canReadChannel(10, 4)).resolves.toBe(true);
			await expect(service.requirePermission(10, 4, 'message_manage')).rejects.toThrow(ForbiddenException);
		});

		it('채널 원본 정보가 없으면 DM 멤버십이 남아 있어도 거부한다', async () => {
			channelRepository.findByIds.mockResolvedValue([]);
			dmParticipantRepository.findByChannelIdsAndUserIds.mockResolvedValue(new Map([
				[99, [{
					channelId: 99, userId: 5, teamId: null, channelType: 'DM',
				}]],
			]));

			await expect(service.canReadChannel(99, 5)).resolves.toBe(false);
		});
	});

	describe('evaluateMany', () => {
		it('teamId/type 조합이 정확한 DM만 멤버십으로 읽을 수 있다', async () => {
			channelRepository.findByIds.mockResolvedValue([
				{
					channelId: 30, teamId: null, type: 'TEXT', isPrivate: false,
				},
				{
					channelId: 31, teamId: 1, type: 'DM', isPrivate: false,
				},
				{
					channelId: 32, teamId: null, type: 'DM', isPrivate: false,
				},
			]);
			dmParticipantRepository.findByChannelIdsAndUserIds.mockResolvedValue(new Map([
				[30, [{channelId: 30, userId: 5}]],
				[31, [{channelId: 31, userId: 5}]],
				[32, [{channelId: 32, userId: 5}]],
			]));

			const access = await service.evaluateMany([30, 31, 32].map(channelId => ({channelId, userId: 5})));

			expect(access.get('30:5')).toBe(false);
			expect(access.get('31:5')).toBe(false);
			expect(access.get('32:5')).toBe(true);
		});
	});

	describe('검색 범위 채널 조회', () => {
		it('권한 projection이 준비되면 읽을 수 있는 팀 채널만 반환한다', async () => {
			channelRepository.findIdsByTeamId.mockResolvedValue([10]);

			await expect(service.requireReadableTeamChannelIds(1, 2)).resolves.toEqual([10]);
			expect(channelRepository.findIdsByTeamId).toHaveBeenCalledWith(1);
		});

		it('판정 전 readiness가 닫혀 있으면 빈 결과 대신 503을 던진다', async () => {
			projectionReadiness.areReady.mockReturnValue(false);

			await expect(service.requireReadableTeamChannelIds(1, 2)).rejects.toBeInstanceOf(ServiceUnavailableException);
			await expect(service.requireReadableProjectChannelIds(7, 2)).rejects.toBeInstanceOf(ServiceUnavailableException);
			expect(channelRepository.findIdsByTeamId).not.toHaveBeenCalled();
			expect(channelRepository.findIdsByProjectId).not.toHaveBeenCalled();
		});

		it.each([
			['팀', async () => service.requireReadableTeamChannelIds(1, 2), channelRepository.findIdsByTeamId],
			['프로젝트', async () => service.requireReadableProjectChannelIds(7, 2), channelRepository.findIdsByProjectId],
		])('%s 채널 판정 도중 readiness가 닫히면 503을 던진다', async (_label, find, findIds) => {
			findIds.mockImplementationOnce(async () => {
				projectionReadiness.areReady.mockReturnValue(false);
				return [10];
			});

			await expect(find()).rejects.toBeInstanceOf(ServiceUnavailableException);
		});
	});

	describe('API 권한 평가', () => {
		it.each(['read', 'visible'] as const)('%s 필터는 미준비 상태를 빈 목록으로 바꾸지 않는다', async mode => {
			projectionReadiness.areReady.mockReturnValue(false);
			const read = mode === 'read'
				? service.filterReadableChannelIds(2, [])
				: service.filterVisibleChannelIds(2, []);

			await expect(read).rejects.toBeInstanceOf(ServiceUnavailableException);
		});

		it('평가 중 미준비를 관측하면 복구 후에도 권한 거부로 바꾸지 않는다', async () => {
			projectionReadiness.areReady.mockReturnValueOnce(true).mockReturnValueOnce(false).mockReturnValue(true);

			await expect(service.filterReadableChannelIds(2, [10])).rejects.toBeInstanceOf(ServiceUnavailableException);
		});

		it('평가 도중 미준비가 되면 허용 결과를 반환하지 않는다', async () => {
			channelRepository.findByIds.mockImplementationOnce(async () => {
				projectionReadiness.areReady.mockReturnValue(false);
				return [{
					channelId: 10, teamId: 1, type: 'TEXT', isPrivate: false,
				}];
			});

			await expect(service.filterVisibleChannelIds(2, [10])).rejects.toBeInstanceOf(ServiceUnavailableException);
		});

		it('내부 수신자 판정은 미준비 상태에서 접근을 거부한다', async () => {
			projectionReadiness.areReady.mockReturnValue(false);

			await expect(service.canReadChannel(10, 2)).resolves.toBe(false);
		});
	});

	describe('evictUnauthorizedSockets', () => {
		it('권한 회수 시 거부된 socket만 채널 room에서 강제 퇴장시킨다', async () => {
			const allowedSocket = {
				data: {userId: 2},
				rooms: new Set(['chat:10']),
				leave: jest.fn().mockResolvedValue(undefined),
				emit: jest.fn(),
			};
			const deniedSocket = {
				data: {userId: 4},
				rooms: new Set(['chat:10']),
				leave: jest.fn().mockResolvedValue(undefined),
				emit: jest.fn(),
			};
			const io = {
				in: jest.fn().mockReturnValue({fetchSockets: jest.fn().mockResolvedValue([allowedSocket, deniedSocket])}),
			};

			await service.evictUnauthorizedSockets(io as never, [10]);

			expect(allowedSocket.leave).not.toHaveBeenCalled();
			expect(deniedSocket.leave).toHaveBeenCalledWith('chat:10');
			expect(deniedSocket.emit).toHaveBeenCalledWith('channel:access:revoked', {channelId: 10});
		});

		it.each([
			['판정 전 readiness가 닫혀 있으면', () => projectionReadiness.areReady.mockReturnValue(false)],
			['판정 도중 readiness가 닫히면', () => channelRepository.findByIds.mockImplementationOnce(async () => {
				projectionReadiness.areReady.mockReturnValue(false);
				return [{
					channelId: 10, teamId: 1, type: 'TEXT', isPrivate: false,
				}];
			})],
		])('%s 거부 판정으로 socket을 제거하지 않는다', async (_label, closeReadiness) => {
			closeReadiness();
			const socket = {
				data: {userId: 3}, rooms: new Set(['chat:10']), leave: jest.fn(), emit: jest.fn(),
			};
			const io = {in: jest.fn().mockReturnValue({fetchSockets: jest.fn().mockResolvedValue([socket])})};

			await service.evictUnauthorizedSockets(io as never, [10]);

			expect(socket.leave).not.toHaveBeenCalled();
			expect(socket.emit).not.toHaveBeenCalled();
		});
	});

	describe('evictUnauthorizedRooms', () => {
		const makeSocket = (userId: number, rooms: string[]) => ({
			data: {userId},
			rooms: new Set(rooms),
			leave: jest.fn(),
			emit: jest.fn(),
		});

		it('현재 권한이 없는 채널·팀 room에서만 socket을 제거한다', async () => {
			const allowed = makeSocket(2, ['socket-a', 'user:2', 'chat:10', 'team:1']);
			const denied = makeSocket(4, ['socket-b', 'user:4', 'chat:10', 'team:2']);

			await expect(service.evictUnauthorizedRooms([allowed, denied])).resolves.toBe(2);

			expect(allowed.leave).not.toHaveBeenCalled();
			expect(denied.leave.mock.calls).toEqual([['chat:10'], ['team:2']]);
			expect(denied.emit).toHaveBeenCalledWith('channel:access:revoked', {channelId: 10});
			expect(denied.emit).toHaveBeenCalledWith('team:access:revoked', {teamId: 2});
		});

		it('판정 도중 readiness가 닫히면 그 판정으로 room을 제거하지 않는다', async () => {
			channelRepository.findByIds.mockImplementationOnce(async () => {
				projectionReadiness.areReady.mockReturnValue(false);
				return [{
					channelId: 10, teamId: 1, type: 'TEXT', isPrivate: false,
				}];
			});
			const denied = makeSocket(4, ['chat:10']);

			await expect(service.evictUnauthorizedRooms([denied])).resolves.toBe(0);

			expect(denied.leave).not.toHaveBeenCalled();
		});
	});

	describe('emitToReadableChannelUsers', () => {
		it('채널 room에 그대로 보낸다 — room 멤버십이 곧 판정 결과이므로 전송 시점에 다시 평가하지 않는다', async () => {
			const emit = jest.fn();
			const to = jest.fn().mockReturnValue({emit});
			const io = {to};

			await expect(service.emitToReadableChannelUsers(io as never, 10, {event: 'message', payload: {value: 1}})).resolves.toBe(true);

			expect(to).toHaveBeenCalledWith('chat:10');
			expect(emit).toHaveBeenCalledWith('message', {value: 1});
		});

		it('projection readiness가 닫혀 있는 동안은 전송하지 않는다(fail-closed)', async () => {
			projectionReadiness.areReady.mockReturnValue(false);
			const emit = jest.fn();
			const to = jest.fn().mockReturnValue({emit});

			await expect(service.emitToReadableChannelUsers({to} as never, 10, {event: 'message', payload: {value: 1}})).resolves.toBe(false);

			expect(to).not.toHaveBeenCalled();
			expect(emit).not.toHaveBeenCalled();
		});

		it('닫혀 있던 구간 뒤 첫 전송 전에 로컬 room을 재검증하고, 그 뒤에 전송한다', async () => {
			const order: string[] = [];
			const sockets = [{id: 's1'}];
			const evict = jest.spyOn(service, 'evictUnauthorizedRooms').mockImplementation(async () => {
				order.push('sweep');
				return 1;
			});
			const emit = jest.fn(() => {
				order.push('emit');
			});
			const io = {local: {fetchSockets: jest.fn().mockResolvedValue(sockets)}, to: jest.fn().mockReturnValue({emit})};

			projectionReadiness.areReady.mockReturnValue(false);
			await service.emitToReadableChannelUsers(io as never, 10, {event: 'message', payload: {value: 1}});
			projectionReadiness.areReady.mockReturnValue(true);
			await service.emitToReadableChannelUsers(io as never, 10, {event: 'message', payload: {value: 2}});
			await service.emitToReadableChannelUsers(io as never, 10, {event: 'message', payload: {value: 3}});

			expect(order).toEqual(['sweep', 'emit', 'emit']);
			expect(evict).toHaveBeenCalledTimes(1);
			expect(evict).toHaveBeenCalledWith(sockets);
		});

		it('재검증 도중 readiness가 다시 닫히면 전송하지 않고 다음 전송에서 재검증한다', async () => {
			const evict = jest.spyOn(service, 'evictUnauthorizedRooms').mockImplementation(async () => {
				projectionReadiness.areReady.mockReturnValue(false);
				return 0;
			});
			const emit = jest.fn();
			const io = {local: {fetchSockets: jest.fn().mockResolvedValue([])}, to: jest.fn().mockReturnValue({emit})};

			projectionReadiness.areReady.mockReturnValue(false);
			await service.emitToReadableChannelUsers(io as never, 10, {event: 'message', payload: {value: 1}});
			projectionReadiness.areReady.mockReturnValue(true);
			await expect(service.emitToReadableChannelUsers(io as never, 10, {event: 'message', payload: {value: 2}})).resolves.toBe(false);
			expect(emit).not.toHaveBeenCalled();

			evict.mockImplementation(async () => 0);
			projectionReadiness.areReady.mockReturnValue(true);
			await service.emitToReadableChannelUsers(io as never, 10, {event: 'message', payload: {value: 3}});

			expect(evict).toHaveBeenCalledTimes(2);
			expect(emit).toHaveBeenCalledTimes(1);
		});

		it('Socket.IO 서버가 없으면 전송하지 않았다고 알린다', async () => {
			await expect(service.emitToReadableChannelUsers(undefined, 10, {event: 'message', payload: {value: 1}})).resolves.toBe(false);
		});

		it('보낸 사람의 socket은 제외한다', async () => {
			const emit = jest.fn();
			const except = jest.fn().mockReturnValue({emit});
			const to = jest.fn().mockReturnValue({except});
			const io = {to};

			await service.emitToReadableChannelUsers(io as never, 10, {event: 'typing', payload: {value: true}, excludedSocketId: 'sender'});

			expect(to).toHaveBeenCalledWith('chat:10');
			expect(except).toHaveBeenCalledWith('sender');
			expect(emit).toHaveBeenCalledWith('typing', {value: true});
		});
	});

	describe('emitToActiveTeamUsers', () => {
		it('팀 범위 이벤트는 탈퇴한 구독자를 제외하고 활성 팀 멤버에게만 보낸다', async () => {
			const active = {id: 'active', data: {userId: 2}, emit: jest.fn()};
			const stale = {id: 'stale', data: {userId: 99}, emit: jest.fn()};
			const io = {
				in: jest.fn().mockReturnValue({fetchSockets: jest.fn().mockResolvedValue([active, stale])}),
			};
			teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValueOnce([
				{
					teamId: 1, userId: 2, role: 'MEMBER', teamName: 'team',
				},
			]);

			await service.emitToActiveTeamUsers(io as never, 1, 'project:updated', {projectId: 5});

			expect(teamMemberRepository.findByTeamIdsAndUserIds).toHaveBeenCalledWith([1], [2, 99]);
			expect(active.emit).toHaveBeenCalledWith('project:updated', {projectId: 5});
			expect(stale.emit).not.toHaveBeenCalled();
		});
	});

	describe('voice channel logs', () => {
		const voiceChannel = {
			channelId: 30, teamId: 1, type: 'VOICE', isPrivate: false,
		};
		const textChannel = {
			channelId: 10, teamId: 1, type: 'TEXT', isPrivate: false,
		};
		const findChannels = async (ids: number[]) => [voiceChannel, textChannel].filter(channel => ids.includes(channel.channelId));

		beforeEach(() => {
			channelRepository.findByIds.mockImplementation(findChannels);
			dmParticipantRepository.findByChannelIdsAndUserIds.mockResolvedValue(new Map());
			dmParticipantRepository.findByChannelIds.mockResolvedValue(new Map());
			const members = [8, 9].map(userId => member(userId));
			teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue(members);
			teamMemberRepository.findByTeamIds.mockResolvedValue(members);
			setDecisions([
				decision(30, 8, ['voice_connect', 'message_read', 'voice_log_read']),
				decision(30, 9, ['voice_connect', 'message_read']),
				decision(10, 8, ['message_read']),
				decision(10, 9, ['message_read']),
			]);
		});

		it('voice_log_read가 없는 사용자에게만 음성 채널을 로그 숨김 채널로 돌려준다', async () => {
			await expect(service.requireVoiceLogHiddenChannelIds(9, [30, 10])).resolves.toEqual(new Set([30]));
			await expect(service.requireVoiceLogHiddenChannelIds(8, [30, 10])).resolves.toEqual(new Set());
		});

		it('음성 채널이 없으면 권한을 판정하지 않는다', async () => {
			await expect(service.requireVoiceLogHiddenChannelIds(9, [10])).resolves.toEqual(new Set());
			expect(teamMemberRepository.findByTeamIdsAndUserIds).not.toHaveBeenCalled();
		});

		it('판정 결과가 voice_log_read를 허용하면 로그를 숨기지 않는다', async () => {
			setDecisions([decision(30, 9, ['voice_connect', 'message_read', 'voice_log_read'])]);

			await expect(service.requireVoiceLogHiddenChannelIds(9, [30])).resolves.toEqual(new Set());
		});

		it('SYSTEM 메시지 수신자를 구할 때 음성 채널 로그를 볼 수 없는 사용자를 뺀다', async () => {
			await expect(service.findReadableUsersByChannel([30], {voiceLogs: true})).resolves.toEqual(new Map([[30, [8]]]));
			await expect(service.findReadableUsersByChannel([30])).resolves.toEqual(new Map([[30, [8, 9]]]));
		});

		it('텍스트 채널의 SYSTEM 메시지 수신자에는 voice_log_read를 요구하지 않는다', async () => {
			await expect(service.findReadableUsersByChannel([10], {voiceLogs: true})).resolves.toEqual(new Map([[10, [8, 9]]]));
		});

		it('음성 채널 SYSTEM 메시지는 room 소켓 중 로그를 볼 수 있는 사용자에게만 보낸다', async () => {
			const reader = {id: 'a', data: {userId: 8}, emit: jest.fn()};
			const nonReader = {id: 'b', data: {userId: 9}, emit: jest.fn()};
			const to = jest.fn();
			const io = {in: jest.fn().mockReturnValue({fetchSockets: jest.fn().mockResolvedValue([reader, nonReader])}), to};
			const payload = {type: 'SYSTEM', content: '음성 통화가 시작되었습니다.'};

			await expect(service.emitToReadableChannelUsers(io as never, 30, {event: 'message', payload})).resolves.toBe(true);

			expect(io.in).toHaveBeenCalledWith('chat:30');
			expect(reader.emit).toHaveBeenCalledWith('message', payload);
			expect(nonReader.emit).not.toHaveBeenCalled();
			expect(to).not.toHaveBeenCalled();
		});

		it('음성 채널 SYSTEM 메시지 판정 중 projection이 닫히면 보내지 않고 false를 돌려준다', async () => {
			const reader = {id: 'a', data: {userId: 8}, emit: jest.fn()};
			const io = {in: jest.fn().mockReturnValue({fetchSockets: jest.fn().mockResolvedValue([reader])}), to: jest.fn()};
			const payload = {type: 'SYSTEM', content: '음성 통화가 시작되었습니다.'};
			channelAccessRepository.findByChannelIdsAndUserIds.mockImplementation(async () => {
				projectionReadiness.areReady.mockReturnValue(false);
				return [decision(30, 8, ['voice_connect', 'message_read', 'voice_log_read'])];
			});

			await expect(service.emitToReadableChannelUsers(io as never, 30, {event: 'message', payload})).resolves.toBe(false);

			expect(reader.emit).not.toHaveBeenCalled();
		});

		it('음성 채널의 일반 메시지는 room에 그대로 보낸다', async () => {
			const emit = jest.fn();
			const io = {to: jest.fn().mockReturnValue({emit}), in: jest.fn()};

			await service.emitToReadableChannelUsers(io as never, 30, {event: 'message', payload: {type: 'TEXT'}});

			expect(io.to).toHaveBeenCalledWith('chat:30');
			expect(io.in).not.toHaveBeenCalled();
		});
	});
});
