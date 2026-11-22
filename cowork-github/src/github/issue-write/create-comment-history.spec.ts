import type {Redis} from 'ioredis';
import {CREATE_COMMENT_HISTORY_TTL_SECONDS, githubCacheKeys, SET_GITHUB_CACHE_VALUE_IF_MATCHES} from '../github.cache.js';
import {CreateCommentHistory, type CreateCommentRecord} from './create-comment-history.js';

type ReceivedRecord = Extract<CreateCommentRecord, {state: 'RECEIVED'}>;

describe('CreateCommentHistory', () => {
	const operationId = '11111111-1111-4111-8111-111111111111';
	const key = githubCacheKeys.createCommentHistory(operationId);
	let store: Map<string, string>;
	let redis: {set: jest.Mock; get: jest.Mock; eval: jest.Mock};
	let history: CreateCommentHistory;

	const stored = (): CreateCommentRecord => JSON.parse(store.get(key)!) as CreateCommentRecord;

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
		history = new CreateCommentHistory(redis as unknown as Redis);
	});

	it('처음 받은 명령은 claimId를 붙여 RECEIVED로 선점한다', async () => {
		const {record, claimed} = await history.claim(operationId, 'fp');

		expect(claimed).toBe(true);
		expect(record).toMatchObject({state: 'RECEIVED', fingerprint: 'fp', claimId: expect.any(String) as unknown});
		expect(stored()).toEqual(record);
		expect(redis.set).toHaveBeenCalledWith(key, JSON.stringify(record), 'EX', CREATE_COMMENT_HISTORY_TTL_SECONDS, 'NX');
	});

	it('재시도 표시가 없는 RECEIVED는 결과를 모르므로 다시 선점하지 않는다', async () => {
		const first = await history.claim(operationId, 'fp');

		const again = await history.claim(operationId, 'fp');

		expect(again).toEqual({record: first.record, claimed: false});
	});

	it('호출 한도로 거절된 기록은 새 claimId로 다시 선점하고 재시도 표시를 지운다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.markRetryable(operationId, first.record as ReceivedRecord);
		expect(stored()).toMatchObject({state: 'RECEIVED', isRetryable: true, claimId: first.record.claimId});

		const again = await history.claim(operationId, 'fp');

		expect(again.claimed).toBe(true);
		expect(again.record.claimId).not.toBe(first.record.claimId);
		expect(again.record).not.toHaveProperty('isRetryable');
		expect(stored()).toEqual(again.record);
		expect(redis.eval).toHaveBeenLastCalledWith(
			SET_GITHUB_CACHE_VALUE_IF_MATCHES, 1, key, expect.any(String), JSON.stringify(again.record), CREATE_COMMENT_HISTORY_TTL_SECONDS,
		);
	});

	it('재시도 표시가 있어도 fingerprint가 다르면 선점하지 않고 기존 기록을 돌려준다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.markRetryable(operationId, first.record as ReceivedRecord);
		const marked = stored();

		const other = await history.claim(operationId, 'other');

		expect(other).toEqual({record: marked, claimed: false});
		expect(stored()).toEqual(marked);
	});

	it('다시 선점한 뒤에 이전 선점의 재시도 표시는 기록을 바꾸지 않는다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.markRetryable(operationId, first.record as ReceivedRecord);
		const again = await history.claim(operationId, 'fp');

		await history.markRetryable(operationId, first.record as ReceivedRecord);

		expect(stored()).toEqual(again.record);
	});

	it('다시 선점하는 사이에 기록이 바뀌면 오류를 던진다', async () => {
		const first = await history.claim(operationId, 'fp');
		await history.markRetryable(operationId, first.record as ReceivedRecord);
		redis.eval.mockResolvedValueOnce(0);

		await expect(history.claim(operationId, 'fp')).rejects.toThrow('Create-comment history changed during claim');
	});

	it('claimId가 없는 이전 기록은 결과를 모르는 RECEIVED로 다룬다', async () => {
		const previous = {state: 'RECEIVED', fingerprint: 'fp', receivedAt: '2026-11-01T00:00:00.000Z'};
		store.set(key, JSON.stringify(previous));

		await expect(history.claim(operationId, 'fp')).resolves.toEqual({record: previous, claimed: false});
	});

	it('결과를 확정한 기록은 다시 선점하지 않는다', async () => {
		const first = await history.claim(operationId, 'fp');
		const event = {status: 'SUCCEEDED'} as unknown as Extract<CreateCommentRecord, {state: 'COMPLETED'}>['event'];
		await history.save(operationId, {...first.record, state: 'COMPLETED', event});

		await history.markRetryable(operationId, first.record as ReceivedRecord);
		const again = await history.claim(operationId, 'fp');

		expect(again.claimed).toBe(false);
		expect(again.record).toMatchObject({state: 'COMPLETED'});
		expect(again.record).not.toHaveProperty('isRetryable');
	});
});
