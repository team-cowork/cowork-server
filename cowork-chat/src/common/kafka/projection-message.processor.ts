import { KafkaMessage } from 'kafkajs';
import { ProjectionReadinessService, ProjectionStream } from './projection-readiness.service';

export const SNAPSHOT_BARRIER_KEY_PREFIX = '__cowork_projection_snapshot_complete__:';
export const SNAPSHOT_BARRIER_EVENT_TYPE = 'PROJECTION_SNAPSHOT_COMPLETED';

export interface ProjectionMessageResult {
    snapshotBarrier?: {
        offset: string;
        snapshotId: string;
        source: string;
        occurredAt: Date;
    };
}

interface SnapshotBarrierPayload {
    eventType: typeof SNAPSHOT_BARRIER_EVENT_TYPE;
    topic: string;
    partition: number;
    snapshotId: string;
    occurredAt: string;
    source: string;
}

export class ProjectionContractError extends Error {
    constructor(message: string) {
        super(message);
        this.name = ProjectionContractError.name;
    }
}

/** JSON/계약 오류는 durable quarantine하고, 저장소·런타임 오류는 consumer까지 전파한다. */
export async function applyProjectionMessage(
    stream: ProjectionStream,
    partition: number,
    message: KafkaMessage,
    readiness: ProjectionReadinessService,
    apply: (payload: unknown, messageKey?: string) => Promise<void>,
): Promise<ProjectionMessageResult> {
    const eventKey = message.key?.toString() ?? null;
    const rawPayload = message.value?.toString() ?? null;

    if (rawPayload === null) {
        await readiness.quarantine(
            stream,
            partition,
            message.offset,
            eventKey,
            null,
            'message value is required; logical tombstone payload expected',
        );
        return {};
    }

    try {
        const payload = JSON.parse(rawPayload) as unknown;
        if (eventKey?.startsWith(SNAPSHOT_BARRIER_KEY_PREFIX)
            || isSnapshotBarrierEvent(payload)) {
            const barrier = validateSnapshotBarrier(stream, partition, eventKey, payload);
            return {
                snapshotBarrier: {
                    offset: message.offset,
                    snapshotId: barrier.snapshotId,
                    source: barrier.source,
                    occurredAt: new Date(barrier.occurredAt),
                },
            };
        }
        await apply(payload, eventKey ?? undefined);
        return {};
    } catch (error) {
        if (!(error instanceof SyntaxError) && !(error instanceof ProjectionContractError)) throw error;
        await readiness.quarantine(
            stream,
            partition,
            message.offset,
            eventKey,
            rawPayload,
            error.message,
        );
        return {};
    }
}

function isSnapshotBarrierEvent(payload: unknown): boolean {
    return typeof payload === 'object'
        && payload !== null
        && 'eventType' in payload
        && payload.eventType === SNAPSHOT_BARRIER_EVENT_TYPE;
}

function validateSnapshotBarrier(
    stream: ProjectionStream,
    partition: number,
    eventKey: string | null,
    payload: unknown,
): SnapshotBarrierPayload {
    const expectedKey = `${SNAPSHOT_BARRIER_KEY_PREFIX}${partition}`;
    if (eventKey !== expectedKey || typeof payload !== 'object' || payload === null) {
        throw new ProjectionContractError(
            `invalid projection snapshot marker [key=${eventKey ?? '<missing>'}, expected=${expectedKey}]`,
        );
    }
    const event = payload as Partial<SnapshotBarrierPayload>;
    const occurredAt = typeof event.occurredAt === 'string' ? new Date(event.occurredAt) : null;
    if (event.eventType !== SNAPSHOT_BARRIER_EVENT_TYPE
        || event.topic !== stream.topic
        || event.partition !== partition
        || typeof event.snapshotId !== 'string'
        || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(event.snapshotId)
        || !occurredAt
        || Number.isNaN(occurredAt.getTime())
        || typeof event.source !== 'string'
        || event.source !== stream.expectedSource) {
        throw new ProjectionContractError('invalid projection snapshot marker payload');
    }
    return event as SnapshotBarrierPayload;
}
