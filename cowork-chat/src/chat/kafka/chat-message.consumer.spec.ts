import { ConfigService } from '@nestjs/config';
import { DicoshotService } from 'dicoshot-nest';
import { KafkaMessage } from 'kafkajs';
import { ChatMessageConsumer } from './chat-message.consumer';
import { ChatMessageProcessor } from './chat-message.processor';
import { ChatMessageQuarantineService } from '../service/chat-message-quarantine.service';

const event = () => ({
    contractVersion: 1,
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

function kafkaMessage(value: string | null, key = '42'): KafkaMessage {
    return {
        key: Buffer.from(key), value: value === null ? null : Buffer.from(value),
        offset: '3', timestamp: '0', headers: {}, attributes: 0,
    };
}

describe('ChatMessageConsumer poison quarantine boundary', () => {
    const processor = { process: jest.fn(), setSocketServer: jest.fn() };
    const quarantine = { quarantine: jest.fn().mockResolvedValue(undefined) };
    let consumer: ChatMessageConsumer;

    beforeEach(() => {
        jest.clearAllMocks();
        consumer = new ChatMessageConsumer(
            {} as ConfigService,
            {} as DicoshotService,
            processor as unknown as ChatMessageProcessor,
            quarantine as unknown as ChatMessageQuarantineService,
        );
    });

    it.each([
        ['content', { content: 1 }, 'INVALID_CONTENT'],
        ['channelId', { channelId: 0 }, 'INVALID_CHANNEL_ID'],
        ['attachments', { attachments: [{}] }, 'INVALID_ATTACHMENTS'],
    ])('유효 JSON이지만 잘못된 %s는 프로세스를 종료하지 않고 격리한다', async (_field, patch, reasonCode) => {
        await expect(consumer.processKafkaMessage('chat.message', 1, kafkaMessage(JSON.stringify({ ...event(), ...patch })))).resolves.toBeUndefined();

        expect(processor.process).not.toHaveBeenCalled();
        expect(quarantine.quarantine).toHaveBeenCalledWith(expect.objectContaining({
            errorType: 'CONTRACT_ERROR', reasonCode, partition: 1, messageOffset: '3',
        }));
    });

    it('Kafka key와 channelId가 다르면 저장·브로드캐스트 전에 격리한다', async () => {
        await consumer.processKafkaMessage('chat.message', 1, kafkaMessage(JSON.stringify(event()), '999'));

        expect(processor.process).not.toHaveBeenCalled();
        expect(quarantine.quarantine).toHaveBeenCalledWith(expect.objectContaining({ reasonCode: 'KEY_CHANNEL_MISMATCH' }));
    });

    it('quarantine 저장 실패는 전파해 offset을 유지한다', async () => {
        quarantine.quarantine.mockRejectedValueOnce(new Error('Mongo unavailable'));

        await expect(consumer.processKafkaMessage('chat.message', 1, kafkaMessage('{'))).rejects.toThrow('Mongo unavailable');
    });

    it('일시적 메시지 저장 오류는 quarantine으로 오분류하지 않고 전파한다', async () => {
        processor.process.mockRejectedValueOnce(new Error('Mongo timeout'));

        await expect(consumer.processKafkaMessage('chat.message', 1, kafkaMessage(JSON.stringify(event())))).rejects.toThrow('Mongo timeout');
        expect(quarantine.quarantine).not.toHaveBeenCalled();
    });

    it('poison 뒤 정상 record는 계속 처리한다', async () => {
        await consumer.processKafkaMessage('chat.message', 1, kafkaMessage('{'));
        await consumer.processKafkaMessage('chat.message', 1, kafkaMessage(JSON.stringify(event())));

        expect(quarantine.quarantine).toHaveBeenCalledTimes(1);
        expect(processor.process).toHaveBeenCalledWith(expect.objectContaining({ channelId: 42, content: 'hello' }));
    });
});
