import {ForbiddenException, ServiceUnavailableException} from '@nestjs/common';
import {Types} from 'mongoose';
import {type Nullable} from '../../common/type/nullable.js';
import {ChannelReadService} from './channel-read.service.js';

describe('ChannelReadService.advance', () => {
	const requestedId = new Types.ObjectId('000000000000000000000001');
	const adoptedId = new Types.ObjectId('000000000000000000000002');

	const joinedAt = new Date(1000);
	const createService = (applied: Nullable<boolean>) => {
		const readStateRepository = {updateLastRead: jest.fn().mockResolvedValue(adoptedId)};
		const messageRepository = {countUnread: jest.fn().mockResolvedValue(1)};
		const unreadCounterService = {set: jest.fn().mockResolvedValue(applied)};
		const channelMessageReadAccess = {requireChannelJoinedAt: jest.fn().mockResolvedValue(joinedAt)};
		const service = new ChannelReadService(
			readStateRepository as never,
			messageRepository as never,
			unreadCounterService as never,
			channelMessageReadAccess as never,
		);
		return {
			service, readStateRepository, messageRepository, unreadCounterService, channelMessageReadAccess,
		};
	};

	it.each([new ForbiddenException(), new ServiceUnavailableException()])('$name 발생 시 기존 읽음 상태를 보존한다', async error => {
		const {service, readStateRepository, unreadCounterService, channelMessageReadAccess} = createService(true);
		channelMessageReadAccess.requireChannelJoinedAt.mockRejectedValue(error);

		await expect(service.advance(1, 42, requestedId)).rejects.toBe(error);
		expect(readStateRepository.updateLastRead).not.toHaveBeenCalled();
		expect(unreadCounterService.set).not.toHaveBeenCalled();
	});

	it('요청값이 아니라 DB가 채택한 읽음 위치로 세고 그 위치와 함께 캐시에 기록한다', async () => {
		const {service, readStateRepository, messageRepository, unreadCounterService} = createService(true);

		const unreadCount = await service.advance(1, 42, requestedId);

		expect(readStateRepository.updateLastRead).toHaveBeenCalledWith(1, 42, requestedId);
		expect(messageRepository.countUnread).toHaveBeenCalledWith(1, adoptedId, false, joinedAt);
		expect(unreadCounterService.set).toHaveBeenCalledWith(1, 42, {count: 1, joinedAt, readCursor: adoptedId.toHexString()});
		expect(unreadCount).toBe(1);
	});

	it('더 최신 읽음 위치의 값이 이미 기록되어 거부되면 null을 반환한다', async () => {
		const {service} = createService(false);

		await expect(service.advance(1, 42, requestedId)).resolves.toBeNull();
	});

	it('Redis 오류로 기록 여부를 모르면 계산한 값을 반환한다', async () => {
		const {service} = createService(null);

		await expect(service.advance(1, 42, requestedId)).resolves.toBe(1);
	});
});
