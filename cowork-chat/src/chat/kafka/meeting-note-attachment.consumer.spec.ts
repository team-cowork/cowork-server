import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {parseMeetingNoteAttachmentEvent} from './meeting-note-attachment.consumer.js';

const key = 'chat-files/1/42/123e4567-e89b-12d3-a456-426614174000.pdf';
const event = {
	noteId: 3, channelId: 1, authorId: 42, attachments: [{objectKey: key, name: '자료.pdf'}], deleted: false, occurredAt: '2026-11-12T00:00:00.000001Z',
};

describe('회의록 첨부 상태 계약', () => {
	it('유효한 참조와 원본 버전을 읽는다', () => {
		expect(parseMeetingNoteAttachmentEvent(event, '3').attachments).toEqual(event.attachments);
	});
	it.each([
		{...event, channelId: 2},
		{...event, authorId: 7},
		{...event, deleted: true},
		{...event, attachments: [null]},
		{...event, occurredAt: 'invalid'},
	])('잘못된 소유자 또는 상태는 격리한다', payload => {
		expect(() => parseMeetingNoteAttachmentEvent(payload, '3')).toThrow(ProjectionContractError);
	});
	it('다른 noteId로 발행한 레코드는 거부한다', () => {
		expect(() => parseMeetingNoteAttachmentEvent(event, '4')).toThrow(ProjectionContractError);
	});
	it('삭제 tombstone은 빈 목록으로 허용한다', () => {
		expect(parseMeetingNoteAttachmentEvent({...event, deleted: true, attachments: []}, '3').deleted).toBe(true);
	});
});
