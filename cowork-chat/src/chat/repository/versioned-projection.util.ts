import { mongo } from 'mongoose';

export const PROJECTION_EPOCH = new Date(0);

const legacySourceVersion = {
    $multiply: [
        { $convert: { input: { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] }, to: 'long' } },
        mongo.Long.fromNumber(1_000_000),
    ],
};
export const projectionSourceVersion = { $ifNull: ['$sourceVersion', legacySourceVersion] };
const deleted = { $ifNull: ['$deleted', false] };

/**
 * 새 active 상태를 적용할 조건. 더 최신인 경우에만 적용하며, 같은 시각이면 tombstone이 우선한다.
 */
export function activeProjectionCondition(sourceVersion: mongo.Long) {
    return {
        $or: [
            { $lt: [projectionSourceVersion, sourceVersion] },
            {
                $and: [
                    { $eq: [projectionSourceVersion, sourceVersion] },
                    { $ne: [deleted, true] },
                ],
            },
        ],
    };
}

/** 같은 시각의 active 상태보다 DELETE가 항상 우선하도록 한다. */
export function deletedProjectionCondition(sourceVersion: mongo.Long) {
    return { $lte: [projectionSourceVersion, sourceVersion] };
}

/** Mongoose 자동 timestamp 갱신이 stale event까지 modified 처리하지 않도록 pipeline에서 직접 관리한다. */
export function projectionTimestampFields(shouldApply: unknown, occurredAt: Date) {
    return {
        createdAt: { $ifNull: ['$createdAt', occurredAt] },
        updatedAt: {
            $cond: [shouldApply, occurredAt, { $ifNull: ['$updatedAt', occurredAt] }],
        },
    };
}

export function projectionUpdateApplied(result: { modifiedCount: number; upsertedCount?: number }): boolean {
    return result.modifiedCount > 0 || (result.upsertedCount ?? 0) > 0;
}
