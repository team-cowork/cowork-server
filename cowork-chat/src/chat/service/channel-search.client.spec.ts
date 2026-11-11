import {ForbiddenException, ServiceUnavailableException} from '@nestjs/common';
import {ChannelSearchClient} from './channel-search.client.js';

describe('ChannelSearchClient', () => {
	const channelRepository = {searchByTeamAndName: jest.fn()};
	const memberRepository = {exists: jest.fn()};
	const accessService = {requireProjectionsReady: jest.fn(), filterVisibleChannelIds: jest.fn()};
	const service = new ChannelSearchClient(channelRepository as never, memberRepository as never, accessService as never);

	beforeEach(() => {
		jest.resetAllMocks();
		memberRepository.exists.mockResolvedValue(true);
	});

	it('미준비 상태에서는 멤버십을 판정하지 않고 503을 반환한다', async () => {
		accessService.requireProjectionsReady.mockImplementation(() => {
			throw new ServiceUnavailableException();
		});

		await expect(service.searchChannels(1, '채널', 2)).rejects.toBeInstanceOf(ServiceUnavailableException);
		expect(memberRepository.exists).not.toHaveBeenCalled();
	});

	it('멤버십 조회 중 미준비가 되면 403 대신 503을 반환한다', async () => {
		memberRepository.exists.mockImplementationOnce(async () => {
			accessService.requireProjectionsReady.mockImplementation(() => {
				throw new ServiceUnavailableException();
			});
			return false;
		});

		await expect(service.searchChannels(1, '채널', 2)).rejects.toBeInstanceOf(ServiceUnavailableException);
	});

	it('준비된 상태에서 팀 멤버가 아니면 403을 반환한다', async () => {
		memberRepository.exists.mockResolvedValue(false);

		await expect(service.searchChannels(1, '채널', 2)).rejects.toBeInstanceOf(ForbiddenException);
	});

	it('검색 중 미준비가 되면 빈 목록 대신 503을 반환한다', async () => {
		channelRepository.searchByTeamAndName.mockResolvedValue([]);
		accessService.filterVisibleChannelIds.mockRejectedValue(new ServiceUnavailableException());

		await expect(service.searchChannels(1, '채널', 2)).rejects.toBeInstanceOf(ServiceUnavailableException);
	});

	it('볼 수 있는 채널만 검색 결과에 포함한다', async () => {
		channelRepository.searchByTeamAndName.mockResolvedValue([
			{
				channelId: 10, name: '공개', type: 'TEXT', viewType: 'CHAT', description: null, isPrivate: false,
			},
			{
				channelId: 20, name: '비공개', type: 'TEXT', viewType: 'CHAT', description: null, isPrivate: true,
			},
		]);
		accessService.filterVisibleChannelIds.mockResolvedValue([10]);

		const result = await service.searchChannels(1, '채널', 2);

		expect(result.map(channel => channel.id)).toEqual([10]);
		expect(accessService.filterVisibleChannelIds).toHaveBeenCalledWith(2, [10, 20]);
	});
});
