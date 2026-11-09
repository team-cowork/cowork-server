import {Buffer} from 'node:buffer';
import {type KafkaMessage} from 'kafkajs';
import {PROJECTION_STREAMS} from './projection-streams.js';
import {applyProjectionMessage} from './projection-message.processor.js';
import {type ProjectionReadinessService} from './projection-readiness.service.js';

const stream = PROJECTION_STREAMS.channel;
const BARRIER_KEY_PREFIX = '__cowork_projection_snapshot_complete__:';
const validBarrier = {
	eventType: 'PROJECTION_SNAPSHOT_COMPLETED',
	topic: stream.topic,
	partition: 0,
	snapshotId: '3f2c9a1e-7b4d-4c8e-9a10-2b6d5e7f8a90',
	occurredAt: '2026-01-01T00:00:00Z',
	source: stream.expectedSource,
};

function message(key: string | undefined, payload: Record<string, unknown>): KafkaMessage {
	return {
		key: key === undefined ? null : Buffer.from(key),
		value: Buffer.from(JSON.stringify(payload)),
		offset: '7',
	} as unknown as KafkaMessage;
}

describe('applyProjectionMessage snapshot barrier 검증', () => {
	it.each([
		['정상 marker는 snapshot barrier로 기록하고 apply하지 않는다', `${BARRIER_KEY_PREFIX}0`, {}, true],
		['key의 partition이 record partition과 다르면 격리한다', `${BARRIER_KEY_PREFIX}1`, {}, false],
		['key가 없는 barrier payload는 격리한다', undefined, {}, false],
		['key prefix가 있어도 eventType이 다르면 격리한다', `${BARRIER_KEY_PREFIX}0`, {eventType: 'OTHER'}, false],
		['topic이 다르면 격리한다', `${BARRIER_KEY_PREFIX}0`, {topic: 'other.topic'}, false],
		['payload partition이 다르면 격리한다', `${BARRIER_KEY_PREFIX}0`, {partition: 1}, false],
		['snapshotId가 UUID 형식이 아니면 격리한다', `${BARRIER_KEY_PREFIX}0`, {snapshotId: 'snapshot-1'}, false],
		['source가 기대값과 다르면 격리한다', `${BARRIER_KEY_PREFIX}0`, {source: 'cowork-user'}, false],
		['occurredAt이 날짜가 아니면 격리한다', `${BARRIER_KEY_PREFIX}0`, {occurredAt: 'not-a-date'}, false],
	])('%s', async (_title, key, overrides, isBarrier) => {
		const quarantine = jest.fn<void, [{partition: number; offset: string; reason: string}]>();
		const readiness = {quarantine};
		const apply = jest.fn();
		const result = await applyProjectionMessage(stream, {
			partition: 0,
			message: message(key, {...validBarrier, ...overrides}),
			readiness: readiness as unknown as ProjectionReadinessService,
			apply,
		});

		expect(apply).not.toHaveBeenCalled();
		if (isBarrier) {
			expect(quarantine).not.toHaveBeenCalled();
			expect(result.snapshotBarrier).toEqual({
				offset: '7',
				snapshotId: validBarrier.snapshotId,
				source: stream.expectedSource,
				occurredAt: new Date(validBarrier.occurredAt),
			});
		} else {
			expect(result).toEqual({});
			expect(quarantine).toHaveBeenCalledTimes(1);
			const [record] = quarantine.mock.lastCall ?? [];
			expect(record).toMatchObject({partition: 0, offset: '7'});
			expect(record?.reason).toContain('invalid projection snapshot marker');
		}
	});
});
