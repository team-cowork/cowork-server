import {BadRequestException, HttpException} from '@nestjs/common';
import {
	decideTagSpam,
	MAX_MENTIONS_PER_WINDOW,
	MENTION_WINDOW_MS,
	TagSpamService,
} from './tag-spam.service.js';

const mentions = (count: number) => Array.from({length: count}, (_, index) => `<@${index + 1}>`).join(' ');

describe('decideTagSpam', () => {
	it('차단이 꺼졌거나 멘션이 없으면 검사하지 않는다', () => {
		expect(decideTagSpam({tagSpamBlock: false, teamRole: 'MEMBER', mentionCount: 50})).toBe('SKIP');
		expect(decideTagSpam({tagSpamBlock: true, teamRole: 'MEMBER', mentionCount: 0})).toBe('SKIP');
	});

	it('OWNER와 ADMIN은 멘션 수와 무관하게 우회한다', () => {
		for (const teamRole of ['OWNER', 'ADMIN']) {
			expect(decideTagSpam({tagSpamBlock: true, teamRole, mentionCount: 50})).toBe('SKIP');
		}
	});

	it('메시지당 상한을 넘으면 바로 거부하고 이하면 시간창을 검사한다', () => {
		expect(decideTagSpam({tagSpamBlock: true, teamRole: 'MEMBER', mentionCount: 11})).toBe('MESSAGE_LIMIT_EXCEEDED');
		expect(decideTagSpam({tagSpamBlock: true, teamRole: 'MEMBER', mentionCount: 10})).toBe('CHECK_WINDOW');
		expect(decideTagSpam({tagSpamBlock: true, teamRole: undefined, mentionCount: 1})).toBe('CHECK_WINDOW');
	});
});

describe('TagSpamService.reserve', () => {
	const settingRepository = {isTagSpamBlockEnabled: jest.fn()};
	const teamMemberRepository = {findByTeamIdsAndUserIds: jest.fn()};
	const rateLimiter = {tryReserveWeighted: jest.fn(), releaseWeighted: jest.fn()};
	const service = Object.assign<TagSpamService, Record<string, unknown>>(new TagSpamService(), {
		settingRepository,
		teamMemberRepository,
		rateLimiter,
	});

	beforeEach(() => {
		jest.clearAllMocks();
		settingRepository.isTagSpamBlockEnabled.mockResolvedValue(true);
		teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([{teamId: 10, userId: 42, role: 'MEMBER'}]);
		rateLimiter.tryReserveWeighted.mockResolvedValue('RESERVED');
	});

	it('중복을 뺀 멘션 수만큼 팀·사용자 시간창에 예약한다', async () => {
		await expect(service.reserve(10, 42, '<@1> <@1> <@2>', 'client-1')).resolves.toEqual({
			key: 'chat:mentionrate:10:42',
			holder: 'client-1',
			weight: 2,
		});
		expect(rateLimiter.tryReserveWeighted).toHaveBeenCalledWith(
			{key: 'chat:mentionrate:10:42', holder: 'client-1', weight: 2}, MENTION_WINDOW_MS, MAX_MENTIONS_PER_WINDOW,
		);
	});

	it('메시지당 상한을 넘으면 예약하지 않고 거부한다', async () => {
		await expect(service.reserve(10, 42, mentions(11), 'client-1')).rejects.toBeInstanceOf(BadRequestException);
		expect(rateLimiter.tryReserveWeighted).not.toHaveBeenCalled();
	});

	it('시간창 합계가 상한을 넘으면 429로 거부한다', async () => {
		rateLimiter.tryReserveWeighted.mockResolvedValue('BUSY');

		await expect(service.reserve(10, 42, mentions(3), 'client-1')).rejects.toMatchObject({status: 429});
		await expect(service.reserve(10, 42, mentions(3), 'client-1')).rejects.toBeInstanceOf(HttpException);
	});

	it('DM, 멘션 없음, 차단 꺼짐, 같은 메시지 재시도는 예약을 돌려주지 않는다', async () => {
		await expect(service.reserve(null, 42, mentions(3), 'client-1')).resolves.toBeUndefined();
		await expect(service.reserve(10, 42, 'hello', 'client-1')).resolves.toBeUndefined();
		expect(settingRepository.isTagSpamBlockEnabled).not.toHaveBeenCalled();

		settingRepository.isTagSpamBlockEnabled.mockResolvedValueOnce(false);
		await expect(service.reserve(10, 42, mentions(30), 'client-1')).resolves.toBeUndefined();

		rateLimiter.tryReserveWeighted.mockResolvedValueOnce('HELD_BY_SAME_HOLDER');
		await expect(service.reserve(10, 42, mentions(3), 'client-1')).resolves.toBeUndefined();
	});

	it('OWNER와 ADMIN은 시간창도 소비하지 않는다', async () => {
		teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([{teamId: 10, userId: 42, role: 'ADMIN'}]);

		await expect(service.reserve(10, 42, mentions(30), 'client-1')).resolves.toBeUndefined();
		expect(rateLimiter.tryReserveWeighted).not.toHaveBeenCalled();
	});
});
