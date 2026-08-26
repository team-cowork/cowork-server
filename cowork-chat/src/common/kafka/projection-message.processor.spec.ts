import { KafkaMessage } from 'kafkajs';
import {
    applyProjectionMessage,
    ProjectionContractError,
    SNAPSHOT_BARRIER_EVENT_TYPE,
    SNAPSHOT_BARRIER_KEY_PREFIX,
} from './projection-message.processor';
import { PROJECTION_STREAMS, ProjectionReadinessService } from './projection-readiness.service';

describe('applyProjectionMessage', () => {
    const stream = PROJECTION_STREAMS.channel;
    const quarantine = jest.fn().mockResolvedValue(undefined);
    const readiness = { quarantine } as unknown as ProjectionReadinessService;

    beforeEach(() => jest.clearAllMocks());

    it('계약 오류 원문을 durable quarantine한 뒤 정상 반환한다', async () => {
        const message = kafkaMessage('{"channelId":3}', '3');
        const apply = jest.fn().mockRejectedValue(new ProjectionContractError('bad key'));

        await expect(applyProjectionMessage(stream, 1, message, readiness, apply)).resolves.toEqual({});

        expect(quarantine).toHaveBeenCalledWith(stream, 1, '7', '3', '{"channelId":3}', 'bad key');
    });

    it('JSON 파싱 오류도 원문을 격리한다', async () => {
        const message = kafkaMessage('{bad', '3');
        const apply = jest.fn();

        await applyProjectionMessage(stream, 1, message, readiness, apply);

        expect(apply).not.toHaveBeenCalled();
        expect(quarantine).toHaveBeenCalledWith(
            stream,
            1,
            '7',
            '3',
            '{bad',
            expect.any(String),
        );
    });

    it('각 partition의 유효한 snapshot marker는 domain handler를 건너뛰고 receipt를 반환한다', async () => {
        const snapshotId = '93b19168-4a63-49cd-b01d-b8d0667a1cb5';
        const payload = JSON.stringify({
            eventType: SNAPSHOT_BARRIER_EVENT_TYPE,
            topic: stream.topic,
            partition: 1,
            snapshotId,
            occurredAt: '2026-08-26T11:00:00.123456Z',
            source: stream.expectedSource,
        });
        const message = kafkaMessage(payload, `${SNAPSHOT_BARRIER_KEY_PREFIX}1`);
        const apply = jest.fn();

        await expect(applyProjectionMessage(stream, 1, message, readiness, apply)).resolves.toEqual({
            snapshotBarrier: {
                offset: '7',
                snapshotId,
                source: stream.expectedSource,
                occurredAt: new Date('2026-08-26T11:00:00.123456Z'),
            },
        });
        expect(apply).not.toHaveBeenCalled();
        expect(quarantine).not.toHaveBeenCalled();
    });

    it('다른 producer의 snapshot marker는 격리하고 receipt로 인정하지 않는다', async () => {
        const payload = JSON.stringify({
            eventType: SNAPSHOT_BARRIER_EVENT_TYPE,
            topic: stream.topic,
            partition: 1,
            snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
            occurredAt: '2026-08-26T11:00:00Z',
            source: 'cowork-deprecated',
        });
        const message = kafkaMessage(payload, `${SNAPSHOT_BARRIER_KEY_PREFIX}1`);

        await expect(applyProjectionMessage(stream, 1, message, readiness, jest.fn())).resolves.toEqual({});
        expect(quarantine).toHaveBeenCalledWith(
            stream,
            1,
            '7',
            `${SNAPSHOT_BARRIER_KEY_PREFIX}1`,
            payload,
            'invalid projection snapshot marker payload',
        );
    });

    it('partition 또는 key가 맞지 않는 snapshot marker는 격리하고 receipt로 인정하지 않는다', async () => {
        const payload = JSON.stringify({
            eventType: SNAPSHOT_BARRIER_EVENT_TYPE,
            topic: stream.topic,
            partition: 0,
            snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
            occurredAt: '2026-08-26T11:00:00Z',
            source: 'cowork-channel',
        });
        const message = kafkaMessage(payload, `${SNAPSHOT_BARRIER_KEY_PREFIX}1`);

        await expect(applyProjectionMessage(stream, 1, message, readiness, jest.fn())).resolves.toEqual({});
        expect(quarantine).toHaveBeenCalledWith(
            stream,
            1,
            '7',
            `${SNAPSHOT_BARRIER_KEY_PREFIX}1`,
            payload,
            'invalid projection snapshot marker payload',
        );
    });

    it('projection 저장소 오류는 격리하지 않고 consumer에 전파한다', async () => {
        const message = kafkaMessage('{"channelId":3}', '3');
        const failure = new Error('Mongo unavailable');
        const apply = jest.fn().mockRejectedValue(failure);

        await expect(applyProjectionMessage(stream, 1, message, readiness, apply)).rejects.toBe(failure);
        expect(quarantine).not.toHaveBeenCalled();
    });

    function kafkaMessage(payload: string | null, key: string | null): KafkaMessage {
        return {
            key: key === null ? null : Buffer.from(key),
            value: payload === null ? null : Buffer.from(payload),
            timestamp: '0',
            attributes: 0,
            offset: '7',
            size: payload?.length ?? 0,
        };
    }
});
