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
    let delayMs = DEFAULT_INITIAL_DELAY_MS;
    for (let tryIndex = 0; ; tryIndex++) {
        try {
            // eslint-disable-next-line no-await-in-loop -- bounded startup retry; each attempt must wait for the previous one to settle.
            return await attempt();
        } catch (error) {
            if (tryIndex >= retries) {
                throw error;
            }

            logger.warn(
                `${label} failed during startup, retrying in ${delayMs}ms `
                + `(${tryIndex + 1}/${retries}): ${error instanceof Error ? error.message : String(error)}`,
            );
            // eslint-disable-next-line no-await-in-loop -- bounded startup retry; each attempt must wait for the previous one to settle.
            await sleep(delayMs);
            delayMs = Math.min(delayMs * 2, DEFAULT_MAX_DELAY_MS);
        }
    }
}
