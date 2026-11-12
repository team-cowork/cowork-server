import {
	BadRequestException,
	ForbiddenException,
	NotFoundException,
	ServiceUnavailableException,
} from '@nestjs/common';
import {MeetingNoteAttachmentDownloadService} from './meeting-note-attachment-download.service.js';
import {ChannelMessageReadAccessService} from './channel-message-read-access.service.js';

const key = 'chat-files/1/42/123e4567-e89b-12d3-a456-426614174000.pdf';

describe('MeetingNoteAttachmentDownloadService', () => {
	const notes = {find: jest.fn()};
	const access = {requireCanView: jest.fn()};
	const readiness = {areReady: jest.fn()};
	const storage = {createPresignedDownload: jest.fn()};
	const service = new MeetingNoteAttachmentDownloadService(notes as never, access as never, readiness as never, storage as never);

	beforeEach(() => {
		jest.resetAllMocks();
		readiness.areReady.mockReturnValue(true);
		notes.find.mockResolvedValue({authorId: 42, attachments: [{objectKey: key, name: '회의자료.pdf'}]});
		storage.createPresignedDownload.mockResolvedValue({downloadUrl: 'signed', expiresInSeconds: 60});
	});

	it('비공개 음성 채널에서 voice_connect만 허용된 멤버도 회의록 첨부를 다운로드한다', async () => {
		const guard = Object.assign(new ChannelMessageReadAccessService(), {
			channelRepository: {
				findByIds: jest.fn().mockResolvedValue([{
					channelId: 1, teamId: 2, type: 'VOICE', isPrivate: true,
				}]),
			},
			dmParticipantRepository: {findByChannelIdsAndUserIds: jest.fn().mockResolvedValue(new Map())},
			teamMemberRepository: {findByTeamIdsAndUserIds: jest.fn().mockResolvedValue([{teamId: 2, userId: 7, role: 'MEMBER'}])},
			teamRoleRepository: {
				findAssignmentsByTeamIdsAndAccountIds: jest.fn().mockResolvedValue([{teamId: 2, accountId: 7, roleId: 3}]),
				findDefaultRolesByTeamIds: jest.fn().mockResolvedValue([]),
				findRolesByIds: jest.fn().mockResolvedValue([{teamId: 2, roleId: 3, priority: 1}]),
			},
			policyRepository: {
				findByChannelIdsAndRoleIds: jest.fn().mockResolvedValue([
					{
						teamId: 2, channelId: 1, roleId: 3, permissions: Object.fromEntries([['voice_connect', true], ['message_read', false]]),
					},
				]),
			},
			projectionReadiness: readiness,
		});
		const download = new MeetingNoteAttachmentDownloadService(notes as never, guard, readiness as never, storage as never);
		await expect(download.execute(1, 3, 7, key)).resolves.toEqual({downloadUrl: 'signed', expiresInSeconds: 60});
	});

	it('회의록을 읽을 수 있는 다른 사용자가 objectKey로 다운로드한다', async () => {
		await expect(service.execute(1, 3, 7, key)).resolves.toEqual({downloadUrl: 'signed', expiresInSeconds: 60});
		expect(access.requireCanView).toHaveBeenCalledWith(1, 7);
		expect(notes.find).toHaveBeenCalledWith(3, 1);
		expect(storage.createPresignedDownload).toHaveBeenCalledWith(key, 1, 42, '회의자료.pdf');
	});

	it('접근 권한이 없으면 조회와 발급을 하지 않는다', async () => {
		access.requireCanView.mockRejectedValue(new ForbiddenException());
		await expect(service.execute(1, 3, 7, key)).rejects.toBeInstanceOf(ForbiddenException);
		expect(notes.find).not.toHaveBeenCalled();
		expect(storage.createPresignedDownload).not.toHaveBeenCalled();
	});

	it('첨부 정보가 동기화되지 않았으면 발급하지 않는다', async () => {
		readiness.areReady.mockReturnValue(false);
		await expect(service.execute(1, 3, 7, key)).rejects.toBeInstanceOf(ServiceUnavailableException);
		expect(storage.createPresignedDownload).not.toHaveBeenCalled();
	});

	it.each([null, {authorId: 42, attachments: []}])('삭제되거나 다른 회의록의 첨부이면 발급하지 않는다', async note => {
		notes.find.mockResolvedValue(note);
		await expect(service.execute(1, 3, 7, key)).rejects.toBeInstanceOf(NotFoundException);
		expect(storage.createPresignedDownload).not.toHaveBeenCalled();
	});

	it.each(['', 'a'.repeat(201), undefined])('잘못된 objectKey를 거부한다', async objectKey => {
		await expect(service.execute(1, 3, 7, objectKey!)).rejects.toBeInstanceOf(BadRequestException);
	});
});
