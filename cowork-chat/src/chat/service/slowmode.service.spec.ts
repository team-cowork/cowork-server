import {HttpException} from '@nestjs/common';
import {canBypassSlowmode, SlowmodeService} from './slowmode.service.js';

const member = {teamId: 10, authorizationVersion: 5n};
const decision = (allowedPermissions: string[], sourceAuthorizationVersion = 5n, teamId = 10) => ({
	channelId: 1, userId: 42, teamId, allowedPermissions, readPrivate: false, sourceAuthorizationVersion,
});

describe('canBypassSlowmode', () => {
	it('현재 판정 결과가 slowmode_bypass를 허용할 때만 우회한다', () => {
		expect(canBypassSlowmode(decision(['message_read', 'slowmode_bypass']), member)).toBe(true);
		expect(canBypassSlowmode(decision(['message_read']), member)).toBe(false);
	});

	it('판정 결과나 활성 멤버십이 없으면 우회하지 않는다', () => {
		expect(canBypassSlowmode(undefined, member)).toBe(false);
		expect(canBypassSlowmode(decision(['slowmode_bypass']), undefined)).toBe(false);
	});

	it('다른 인가 version이나 다른 팀으로 계산된 판정 결과는 쓰지 않는다', () => {
		expect(canBypassSlowmode(decision(['slowmode_bypass'], 4n), member)).toBe(false);
		expect(canBypassSlowmode({...decision(['slowmode_bypass']), sourceAuthorizationVersion: null}, member)).toBe(false);
		expect(canBypassSlowmode(decision(['slowmode_bypass'], 5n, 99), member)).toBe(false);
	});
});

describe('SlowmodeService.reserve', () => {
	const settingRepository = {findSlowmodeSeconds: jest.fn()};
	const teamMemberRepository = {findByTeamIdsAndUserIds: jest.fn()};
	const channelAccessRepository = {findByChannelIdsAndUserIds: jest.fn()};
	const rateLimiter = {tryReserve: jest.fn(), release: jest.fn()};
	const service = Object.assign<SlowmodeService, Record<string, unknown>>(new SlowmodeService(), {
		settingRepository,
		teamMemberRepository,
		channelAccessRepository,
		rateLimiter,
	});

	beforeEach(() => {
		jest.resetAllMocks();
		teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([{...member, userId: 42, role: 'MEMBER'}]);
		channelAccessRepository.findByChannelIdsAndUserIds.mockResolvedValue([decision(['message_read'])]);
	});

	it('슬로우 모드가 꺼져 있거나 DM이면 예약하지 않는다', async () => {
		settingRepository.findSlowmodeSeconds.mockResolvedValue(0);

		await expect(service.reserve(1, 10, 42, 'client-1')).resolves.toBeUndefined();
		await expect(service.reserve(1, null, 42, 'client-1')).resolves.toBeUndefined();
		expect(rateLimiter.tryReserve).not.toHaveBeenCalled();
	});

	it('간격만큼 사용자별 key를 clientMessageId로 예약하고 다른 메시지로 점유 중이면 429를 던진다', async () => {
		settingRepository.findSlowmodeSeconds.mockResolvedValue(30);
		rateLimiter.tryReserve.mockResolvedValueOnce('RESERVED').mockResolvedValueOnce('BUSY');

		await expect(service.reserve(1, 10, 42, 'client-1'))
			.resolves.toEqual({key: 'chat:slowmode:1:42', holder: 'client-1'});
		await expect(service.reserve(1, 10, 42, 'client-2')).rejects.toThrow(HttpException);
		expect(rateLimiter.tryReserve).toHaveBeenCalledWith('chat:slowmode:1:42', 'client-1', 30_000);
	});

	it('같은 clientMessageId의 재시도는 통과시키고 해제할 예약을 돌려주지 않는다', async () => {
		settingRepository.findSlowmodeSeconds.mockResolvedValue(30);
		rateLimiter.tryReserve.mockResolvedValue('HELD_BY_SAME_HOLDER');

		await expect(service.reserve(1, 10, 42, 'client-1')).resolves.toBeUndefined();
	});

	it('판정 결과가 slowmode_bypass를 허용하면 예약하지 않는다', async () => {
		settingRepository.findSlowmodeSeconds.mockResolvedValue(30);
		channelAccessRepository.findByChannelIdsAndUserIds.mockResolvedValue([decision(['message_read', 'slowmode_bypass'])]);

		await expect(service.reserve(1, 10, 42, 'client-1')).resolves.toBeUndefined();
		expect(channelAccessRepository.findByChannelIdsAndUserIds).toHaveBeenCalledWith([1], [42]);
		expect(rateLimiter.tryReserve).not.toHaveBeenCalled();
	});
});
