import {type KafkaContext} from '@nestjs/microservices';
import * as axiosModule from 'axios';
import {type AxiosError, type AxiosResponse} from 'axios';
import {requestGithub, withGithubRateLimitRetry} from './github-rate-limit.js';
import {GithubClientError, GithubRateLimitError} from './github.errors.js';

// 실제 시간을 기다리지 않도록 sleep이 가짜 시계를 요청한 시간만큼 앞당긴다.
const mockClock = {now: 0};
const mockSleeps: number[] = [];
const mockSleepControl = {hold: false};

jest.mock('node:timers/promises', () => ({
	async setTimeout(ms: number, _value: unknown, options?: {signal?: AbortSignal}): Promise<void> {
		mockSleeps.push(ms);
		const signal = options?.signal;
		signal?.throwIfAborted();
		if (mockSleepControl.hold) {
			await new Promise((_resolve, reject) => {
				signal?.addEventListener('abort', () => {
					reject(signal.reason as Error);
				}, {once: true});
			});
		}

		mockClock.now += ms;
	},
}));

function githubError(
	status: number,
	headers: Record<string, string> = {},
	message = 'Forbidden',
	axios: typeof axiosModule = axiosModule,
): AxiosError {
	const response: AxiosResponse = {
		status,
		statusText: message,
		headers: new axios.AxiosHeaders(headers),
		data: {message},
		config: {headers: new axios.AxiosHeaders()},
	};
	return new axios.AxiosError('GitHub request failed', undefined, undefined, undefined, response);
}

function kafkaContext(heartbeat: jest.Mock): KafkaContext {
	return {getHeartbeat: () => heartbeat} as unknown as KafkaContext;
}

const sum = (values: number[]): number => values.reduce((total, value) => total + value, 0);

describe('requestGithub', () => {
	let heartbeat: jest.Mock;

	beforeEach(() => {
		mockClock.now = 0;
		mockSleeps.length = 0;
		mockSleepControl.hold = false;
		jest.spyOn(performance, 'now').mockImplementation(() => mockClock.now);
		heartbeat = jest.fn().mockResolvedValue(undefined);
	});

	afterEach(() => {
		jest.restoreAllMocks();
	});

	describe('Kafka 명령 밖에서는', () => {
		it('429를 재시도하지 않고 원래 오류를 던진다', async () => {
			const error = githubError(429, {'Retry-After': '0'});
			const request = jest.fn().mockRejectedValue(error);

			await expect(requestGithub(request)).rejects.toBe(error);
			expect(request).toHaveBeenCalledTimes(1);
			expect(mockSleeps).toEqual([]);
		});

		it('호출 한도 403도 기존처럼 GithubClientError로 바꾼다', async () => {
			const request = jest.fn().mockRejectedValue(githubError(403, {'Retry-After': '0'}));

			await expect(requestGithub(request)).rejects.toMatchObject({name: 'GithubClientError', statusCode: 403});
			expect(request).toHaveBeenCalledTimes(1);
		});
	});

	describe('Kafka 명령 안에서는', () => {
		const run = async <T>(request: () => Promise<T>): Promise<T> =>
			withGithubRateLimitRetry(kafkaContext(heartbeat), async () => requestGithub(request));

		it('권한 오류 403은 재시도하지 않는다', async () => {
			const request = jest.fn().mockRejectedValue(
				githubError(403, {'X-RateLimit-Remaining': '10'}, 'Resource not accessible by integration'),
			);

			await expect(run(request)).rejects.toBeInstanceOf(GithubClientError);
			expect(request).toHaveBeenCalledTimes(1);
			expect(heartbeat).not.toHaveBeenCalled();
		});

		it('호출 한도로 거절되면 GitHub가 준 시간 동안 heartbeat를 보내며 기다린 뒤 다시 요청한다', async () => {
			const request = jest.fn()
				.mockRejectedValueOnce(githubError(429, {'Retry-After': '7'}))
				.mockResolvedValue('created');

			await expect(run(request)).resolves.toBe('created');
			expect(request).toHaveBeenCalledTimes(2);
			expect(mockSleeps).toEqual([3000, 3000, 1000]);
			expect(heartbeat).toHaveBeenCalledTimes(3);
		});

		it('대기 시간을 알려 주지 않으면 1분부터 두 배씩 늘려 세 번까지 요청한다', async () => {
			const request = jest.fn().mockRejectedValue(githubError(429));

			await expect(run(request)).rejects.toBeInstanceOf(GithubRateLimitError);
			expect(request).toHaveBeenCalledTimes(3);
			expect(sum(mockSleeps)).toBe(60_000 + 120_000 + 240_000);
		});

		it('GitHub가 준 시간이 짧아도 1초부터 두 배씩 늘린 시간보다 짧게 기다리지 않는다', async () => {
			const request = jest.fn().mockRejectedValue(githubError(429, {'Retry-After': '0'}));

			await expect(run(request)).rejects.toBeInstanceOf(GithubRateLimitError);
			expect(request).toHaveBeenCalledTimes(3);
			expect(mockSleeps).toEqual([1000, 2000, 3000, 1000]);
		});

		it('안쪽 요청이 호출 한도로 끝나도 바깥 요청은 다시 재시도하지 않는다', async () => {
			const inner = jest.fn().mockRejectedValue(githubError(429, {'Retry-After': '0'}));
			const outer = jest.fn(async () => requestGithub(inner));

			await expect(run(outer)).rejects.toBeInstanceOf(GithubRateLimitError);
			expect(inner).toHaveBeenCalledTimes(3);
			expect(outer).toHaveBeenCalledTimes(1);
		});

		it('heartbeat가 실패하면 기다리기를 멈추고 다시 요청하지 않는다', async () => {
			heartbeat.mockRejectedValue(new Error('The group is rebalancing'));
			const request = jest.fn().mockRejectedValue(githubError(429, {'Retry-After': '60'}));

			await expect(run(request)).rejects.toBeInstanceOf(GithubRateLimitError);
			expect(request).toHaveBeenCalledTimes(1);
			expect(heartbeat).toHaveBeenCalledTimes(1);
		});
	});

	// 종료 신호는 모듈 전체에서 한 번만 쓰이므로, 새로 불러온 모듈로 확인해 다른 테스트에 영향을 주지 않는다.
	it('종료하면 긴 대기를 멈추고 다시 요청하지 않는다', async () => {
		await jest.isolateModulesAsync(async () => {
			// 새로 불러온 모듈은 axios도 새로 불러오므로, 같은 axios로 오류를 만들어야 호출 한도로 판정된다.
			const axios = await import('axios');
			const rateLimit = await import('./github-rate-limit.js');
			mockSleepControl.hold = true;
			const request = jest.fn().mockRejectedValue(githubError(429, {'Retry-After': '3600'}, 'Too Many Requests', axios));

			const outcome = rateLimit.withGithubRateLimitRetry(kafkaContext(heartbeat), async () => rateLimit.requestGithub(request))
				.catch((error: unknown) => error);
			await new Promise(resolve => {
				setImmediate(resolve);
			});
			expect(mockSleeps).toHaveLength(1);
			rateLimit.stopGithubRateLimitWaits();

			await expect(outcome).resolves.toMatchObject({name: 'GithubRateLimitError'});
			expect(request).toHaveBeenCalledTimes(1);
		});
	});
});
