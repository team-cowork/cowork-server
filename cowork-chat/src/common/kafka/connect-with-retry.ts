import {type Logger} from '@nestjs/common';

const DEFAULT_RETRIES = 5;
const DEFAULT_INITIAL_DELAY_MS = 1000;
const DEFAULT_MAX_DELAY_MS = 30_000;

async function sleep(ms: number): Promise<void> {
	return new Promise(resolve => {
		setTimeout(resolve, ms);
	});
}

/**
 Kafkajs retries transient broker errors inside `consumer.run()`, but `connect()`/`subscribe()`/
 projection registration run once during `onModuleInit()` with no retry of their own — a broker
 hiccup at that exact moment (e.g. a leader election mid-flight) crashes the whole NestJS bootstrap
 instead of self-healing, which can turn a few seconds of broker instability into a longer
 container restart loop. This gives that one-shot startup sequence the same kind of bounded,
 backing-off retry kafkajs already applies to the steady-state consumer loop.
 */
export async function connectWithRetry<T>(
	logger: Logger,
	label: string,
	attempt: () => Promise<T>,
	retries = DEFAULT_RETRIES,
): Promise<T> {
	return retryAttempt({
		logger, label, attempt, retries,
	}, 0, DEFAULT_INITIAL_DELAY_MS);
}

type RetryRequest<T> = {
	logger: Logger;
	label: string;
	attempt: () => Promise<T>;
	retries: number;
};

/**
 이전 시도가 끝난 뒤에만 다음 시도를 시작하도록 재귀로 순서를 보장한다.
 */
async function retryAttempt<T>(request: RetryRequest<T>, tryIndex: number, delayMs: number): Promise<T> {
	try {
		return await request.attempt();
	} catch (error) {
		if (tryIndex >= request.retries) {
			throw error;
		}

		request.logger.warn(
			`${request.label} failed during startup, retrying in ${delayMs}ms `
			+ `(${tryIndex + 1}/${request.retries}): ${error instanceof Error ? error.message : String(error)}`,
		);
		await sleep(delayMs);
		return retryAttempt(request, tryIndex + 1, Math.min(delayMs * 2, DEFAULT_MAX_DELAY_MS));
	}
}
