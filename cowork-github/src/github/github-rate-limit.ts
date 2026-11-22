import {AsyncLocalStorage} from 'node:async_hooks';
import {setTimeout as sleep} from 'node:timers/promises';
import {type KafkaContext} from '@nestjs/microservices';
import {type GithubRateLimitError, rethrowGithubError, toGithubRateLimitError} from './github.errors.js';

const commands = new AsyncLocalStorage<KafkaContext>();
const shutdown = new AbortController();
const HEARTBEAT_INTERVAL_MS = 3000;
const MINIMUM_UNSPECIFIED_DELAY_MS = 60_000;
const MAX_ATTEMPTS = 3;

export async function withGithubRateLimitRetry<T>(context: KafkaContext, command: () => Promise<T>): Promise<T> {
	return commands.run(context, command);
}

export function stopGithubRateLimitWaits(): void {
	shutdown.abort();
}

async function waitWithHeartbeat(heartbeat: () => Promise<void>, deadline: number): Promise<void> {
	shutdown.signal.throwIfAborted();
	const remaining = deadline - performance.now();
	if (remaining <= 0) {
		return;
	}

	await sleep(Math.min(HEARTBEAT_INTERVAL_MS, remaining), undefined, {signal: shutdown.signal});
	await heartbeat();
	await waitWithHeartbeat(heartbeat, deadline);
}

/**
 Kafka 명령 안에서 GitHub 호출 한도에 걸리면 기다린 뒤 다시 요청한다.
 `toRateLimitError`는 성공 응답이 호출 한도를 알리는 경우를 판정한다. Kafka 명령 밖에서는 그 응답을 그대로 돌려준다.
 */
export async function requestGithub<T>(
	request: () => Promise<T>,
	toRateLimitError: (response: T) => GithubRateLimitError | undefined = () => undefined,
): Promise<T> {
	const context = commands.getStore();

	const run = async (attempt: number): Promise<T> => {
		let response: T;
		try {
			response = await request();
		} catch (error) {
			if (context === undefined) {
				rethrowGithubError(error);
			}

			const failure = toGithubRateLimitError(error);
			if (failure === undefined) {
				rethrowGithubError(error);
			}

			return waitAndRetry(attempt, context, failure);
		}

		if (context === undefined) {
			return response;
		}

		const failure = toRateLimitError(response);
		return failure === undefined ? response : waitAndRetry(attempt, context, failure);
	};

	async function waitAndRetry(attempt: number, kafka: KafkaContext, failure: GithubRateLimitError): Promise<T> {
		const backoff = 2 ** (attempt - 1);
		const delay = Math.max(failure.retryDelayMs ?? (MINIMUM_UNSPECIFIED_DELAY_MS * backoff), 1000 * backoff);
		// Keep the partition in progress and stop on heartbeat failure before retrying the request.
		await waitWithHeartbeat(kafka.getHeartbeat(), performance.now() + delay).catch(() => {
			throw failure;
		});
		if (attempt >= MAX_ATTEMPTS) {
			throw failure;
		}

		return run(attempt + 1);
	}

	return run(1);
}
