import {AsyncLocalStorage} from 'node:async_hooks';
import {setTimeout as sleep} from 'node:timers/promises';
import {type KafkaContext} from '@nestjs/microservices';
import {rethrowGithubError, toGithubRateLimitError} from './github.errors.js';

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

export async function requestGithub<T>(request: () => Promise<T>): Promise<T> {
	const context = commands.getStore();

	const run = async (attempt: number): Promise<T> => {
		try {
			return await request();
		} catch (error) {
			if (context === undefined) {
				rethrowGithubError(error);
			}

			const failure = toGithubRateLimitError(error);
			if (failure === undefined) {
				rethrowGithubError(error);
			}

			const backoff = 2 ** (attempt - 1);
			const delay = Math.max(failure.retryDelayMs ?? (MINIMUM_UNSPECIFIED_DELAY_MS * backoff), 1000 * backoff);
			// Keep the partition in progress and stop on heartbeat failure before retrying the request.
			await waitWithHeartbeat(context.getHeartbeat(), performance.now() + delay).catch(() => {
				throw failure;
			});
			if (attempt >= MAX_ATTEMPTS) {
				throw failure;
			}

			return run(attempt + 1);
		}
	};

	return run(1);
}
