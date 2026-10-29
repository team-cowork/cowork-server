import {
	BadRequestException,
	ForbiddenException,
	NotFoundException,
	ServiceUnavailableException,
} from '@nestjs/common';
import {AttachmentDownloadService} from './attachment-download.service.js';

const messageId = '123456789012345678901234';
const attachment = {objectKey: 'chat-files/1/42/file.png', name: '회의록.png'};

describe('AttachmentDownloadService', () => {
	const messages = {findByIdAndChannelId: jest.fn()};
	const readAccess = {requireCanRead: jest.fn()};
	const storage = {createPresignedDownload: jest.fn()};
	const service = new AttachmentDownloadService(messages as never, readAccess as never, storage as never);

	beforeEach(() => {
		jest.resetAllMocks();
		readAccess.requireCanRead.mockResolvedValue(undefined);
		messages.findByIdAndChannelId.mockResolvedValue({authorId: 42, attachments: [attachment], searchIndexStatus: 'SYNCED'});
		storage.createPresignedDownload.mockResolvedValue({downloadUrl: 'signed', expiresInSeconds: 60, expiresAt: '2026-10-13T00:01:00.000Z'});
	});

	it('메시지를 읽을 수 있는 사용자는 다른 작성자의 첨부를 내려받는다', async () => {
		const result = await service.execute(1, 7, messageId, 0);
		expect(readAccess.requireCanRead).toHaveBeenCalledWith(1, 7);
		expect(messages.findByIdAndChannelId).toHaveBeenCalledWith(messageId, 1);
		expect(storage.createPresignedDownload).toHaveBeenCalledWith(attachment.objectKey, 1, 42, attachment.name);
		expect(result.expiresInSeconds).toBe(60);
	});

	it.each([new ForbiddenException(), new ServiceUnavailableException()])('읽기 권한이 없거나 불확실하면 발급하지 않는다', async error => {
		readAccess.requireCanRead.mockRejectedValue(error);
		await expect(service.execute(1, 7, messageId, 0)).rejects.toBe(error);
		expect(messages.findByIdAndChannelId).not.toHaveBeenCalled();
		expect(storage.createPresignedDownload).not.toHaveBeenCalled();
	});

	it('같은 사용자의 재요청도 현재 권한으로 판단한다', async () => {
		await service.execute(1, 7, messageId, 0);
		readAccess.requireCanRead.mockRejectedValue(new ForbiddenException());
		await expect(service.execute(1, 7, messageId, 0)).rejects.toBeInstanceOf(ForbiddenException);
		expect(storage.createPresignedDownload).toHaveBeenCalledTimes(1);
	});

	it.each([null, {authorId: 42, attachments: [attachment], searchIndexStatus: 'DELETING'}])('삭제되거나 요청 채널에 없는 메시지는 발급하지 않는다', async message => {
		messages.findByIdAndChannelId.mockResolvedValue(message);
		await expect(service.execute(1, 7, messageId, 0)).rejects.toBeInstanceOf(NotFoundException);
		expect(storage.createPresignedDownload).not.toHaveBeenCalled();
	});

	it('메시지에 등록되지 않은 첨부는 발급하지 않는다', async () => {
		await expect(service.execute(1, 7, messageId, 1)).rejects.toBeInstanceOf(NotFoundException);
		expect(storage.createPresignedDownload).not.toHaveBeenCalled();
	});

	it.each([-1, 0.5, Number.MAX_SAFE_INTEGER + 1])('유효하지 않은 첨부 인덱스는 거부한다', async index => {
		await expect(service.execute(1, 7, messageId, index)).rejects.toBeInstanceOf(BadRequestException);
		expect(storage.createPresignedDownload).not.toHaveBeenCalled();
	});
});
