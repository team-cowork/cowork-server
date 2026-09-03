import { ConfigService } from '@nestjs/config';
import { DicoshotService } from 'dicoshot-nest';
import { Producer } from 'kafkajs';
import { ChatMessageProducer } from './chat-message.producer';
import { validateChatMessageEvent } from './event/chat-message-contract';

type ProducerInternals = { producer: Pick<Producer, 'send'>; isConnected: boolean };

describe('ChatMessageProducer event contract', () => {
    it('consumer validator와 같은 v1 계약 payload를 발행한다', async () => {
        const producer = new ChatMessageProducer({} as ConfigService, {} as DicoshotService);
        const send = jest.fn<ReturnType<Producer['send']>, Parameters<Producer['send']>>().mockResolvedValue([]);
        Object.assign(producer as unknown as ProducerInternals, { producer: { send }, isConnected: true });

        await producer.sendMessage(42, {
            teamId: 10, projectId: null, content: 'hello', type: 'TEXT', attachments: [],
        }, 7, 'MEMBER');

        const value = send.mock.calls[0]?.[0]?.messages[0]?.value;
        if (typeof value !== 'string') throw new Error('producer did not send a string payload');
        expect(validateChatMessageEvent(JSON.parse(value), '42')).toEqual(expect.objectContaining({
            contractVersion: 1, channelId: 42, authorId: 7,
        }));
    });
});
