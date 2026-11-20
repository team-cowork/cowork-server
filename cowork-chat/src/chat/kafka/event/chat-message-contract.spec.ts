import {validateChatMessageEvent} from './chat-message-contract.js';

describe('chat message contract', () => {
	it('작성자 ID로 메시지를 검증한다', () => {
		const message = {
			contractVersion: 3, eventType: 'MESSAGE_SENT', teamId: 1, channelId: 2, authorId: 3,
			content: 'hello', type: 'TEXT', occurredAt: '2026-11-19T00:00:00Z',
		};

		const event = validateChatMessageEvent(message, '2');
		expect(event).toMatchObject(message);
		expect(event).not.toHaveProperty('authorRole');
	});
});
