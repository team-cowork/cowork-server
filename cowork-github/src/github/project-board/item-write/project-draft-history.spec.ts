import type {Redis} from 'ioredis';
import {SET_GITHUB_CACHE_VALUE_IF_MATCHES} from '../../github.cache.js';
import {ProjectDraftHistory, type ProjectDraftRecord} from './project-draft-history.js';

describe('ProjectDraftHistory', () => {
	const operationId = 'op';
	const key = 'github:project-draft:op';
	const ttlSeconds = 14 * 24 * 60 * 60;
	let store: Map<string, string>;
	let redis: {set: jest.Mock; get: jest.Mock; eval: jest.Mock};
	let history: ProjectDraftHistory;

	const stored = (): ProjectDraftRecord => JSON.parse(store.get(key)!) as ProjectDraftRecord;

	beforeEach(() => {
		store = new Map();
		redis = {
			set: jest.fn(async (k: string, value: string, ...options: unknown[]) => {
				if (options.includes('NX') && store.has(k)) {
					return null;
				}

				store.set(k, value);
				return 'OK';
			}),
			get: jest.fn(async (k: string) => store.get(k) ?? null),
			// 저장된 값이 기대한 값과 같을 때만 바꾸는 SET_GITHUB_CACHE_VALUE_IF_MATCHES와 같은 동작이다.
			eval: jest.fn(async (_script: string, ...args: unknown[]) => {
				const [, k, expected, next] = args as [number, string, string, string];
				if (store.get(k) !== expected) {
					return 0;
				}

				store.set(k, next);
				return 1;
			}),
		};
		history = new ProjectDraftHistory(redis as unknown as Redis);
	});

	it('처음 받은 명령은 claimId를 붙여 선점한다', async () => {
		const {record, claimed} = await history.claim(operationId, 'fp');

		expect(claimed).toBe(true);
		expect(record).toEqual({fingerprint: 'fp', claimId: expect.any(String) as unknown});
		expect(stored()).toEqual(record);
		expect(redis.set).toHaveBeenCalledWith(key, JSON.stringify(record), 'EX', ttlSeconds, 'NX');
	});

	it('재시도 표시가 없는 기록은 결과를 모르므로 다시 선점하지 않는다', async () => {
		const first = await history.claim(operationId, 'fp');

		await expect(history.claim(operationId, 'fp')).resolves.toEqual({record: first.record, claimed: false});
	});

	it('호출 한도로 거절된 기록은 새 claimId로 다시 선점하고 재시도 표시를 지운다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.markRetryable(operationId, first.record);
		expect(stored()).toEqual({...first.record, isRetryable: true});

		const again = await history.claim(operationId, 'fp');

		expect(again.claimed).toBe(true);
		expect(again.record.claimId).not.toBe(first.record.claimId);
		expect(again.record).not.toHaveProperty('isRetryable');
		expect(stored()).toEqual(again.record);
		expect(redis.eval).toHaveBeenLastCalledWith(
			SET_GITHUB_CACHE_VALUE_IF_MATCHES, 1, key, expect.any(String), JSON.stringify(again.record), ttlSeconds,
		);
	});

	it('재시도 표시가 있어도 fingerprint가 다르면 선점하지 않고 기존 기록을 돌려준다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.markRetryable(operationId, first.record);
		const marked = stored();

		await expect(history.claim(operationId, 'other')).resolves.toEqual({record: marked, claimed: false});
		expect(stored()).toEqual(marked);
	});

	it('다시 선점한 뒤에 이전 선점의 재시도 표시는 기록을 바꾸지 않는다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.markRetryable(operationId, first.record);
		const again = await history.claim(operationId, 'fp');

		await history.markRetryable(operationId, first.record);

		expect(stored()).toEqual(again.record);
	});

	it('다시 선점하는 사이에 기록이 바뀌면 오류를 던진다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.markRetryable(operationId, first.record);
		redis.eval.mockResolvedValueOnce(0);

		await expect(history.claim(operationId, 'fp')).rejects.toThrow('Project draft history changed during claim');
	});

	it('claimId가 없는 이전 기록은 결과를 모르는 기록으로 다룬다', async () => {
		store.set(key, JSON.stringify({fingerprint: 'fp'}));

		await expect(history.claim(operationId, 'fp')).resolves.toEqual({record: {fingerprint: 'fp'}, claimed: false});
	});

	it('생성 결과를 저장한 기록은 재시도 표시도 다시 선점도 하지 않는다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.save(operationId, {...first.record, itemId: 'PVTI_1'});

		await history.markRetryable(operationId, first.record);
		const again = await history.claim(operationId, 'fp');

		expect(again).toEqual({record: {...first.record, itemId: 'PVTI_1'}, claimed: false});
	});
});
