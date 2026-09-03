import {
    CHAT_MESSAGE_CONTRACT_VERSION,
    ChatMessageContractError,
    validateChatMessageEvent,
} from './chat-message-contract';

const validEvent = () => ({
    contractVersion: CHAT_MESSAGE_CONTRACT_VERSION,
    eventType: 'MESSAGE_SENT',
    teamId: 10,
    projectId: null,
    channelId: 42,
    authorId: 7,
    authorRole: 'MEMBER',
    content: 'hello',
    type: 'TEXT',
    attachments: [],
    occurredAt: '2026-09-03T00:00:00.000Z',
});

describe('validateChatMessageEvent', () => {
    it('현재 계약과 contractVersion이 없는 legacy v1 payload를 검증된 이벤트로 변환한다', () => {
        expect(validateChatMessageEvent(validEvent(), '42')).toEqual(validEvent());

        const legacy = validEvent();
        delete (legacy as Partial<typeof legacy>).contractVersion;
        expect(validateChatMessageEvent(legacy, '42')).toEqual({
            ...validEvent(),
            contractVersion: CHAT_MESSAGE_CONTRACT_VERSION,
        });
    });

    it.each([
        ['content', { content: 1 }, 'INVALID_CONTENT'],
        ['channelId', { channelId: 0 }, 'INVALID_CHANNEL_ID'],
        ['attachments', { attachments: [{ name: 'a', url: 1, size: 1, mimeType: 'text/plain' }] }, 'INVALID_ATTACHMENTS'],
        ['Kafka key', {}, 'KEY_CHANNEL_MISMATCH', '999'],
    ])('%s 계약 위반은 안정적인 reason code로 거부한다', (_name, patch, code, key = '42') => {
        try {
            validateChatMessageEvent({ ...validEvent(), ...patch }, key);
            throw new Error('expected contract error');
        } catch (error) {
            expect(error).toBeInstanceOf(ChatMessageContractError);
            expect((error as ChatMessageContractError).reasonCode).toBe(code);
        }
    });
});
