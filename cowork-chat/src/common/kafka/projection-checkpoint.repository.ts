import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { PartitionOffset } from 'kafkajs';
import { randomUUID } from 'crypto';
import { ProjectionCheckpoint } from './projection-checkpoint.schema';
import { ProjectionQuarantineRecord } from './projection-quarantine.schema';

export interface SnapshotBarrierReceipt {
    offset: string;
    snapshotId: string;
    source: string;
    occurredAt: Date;
}

export interface ProjectionCheckpointOffset extends PartitionOffset {
    datasetGeneration?: string;
    sourceGeneration?: string;
    assignmentEpoch?: string;
    assignmentMemberId?: string;
    assignmentGenerationId?: number;
    snapshotCompletedOffset?: string;
    snapshotId?: string;
    invalidRecordOffset?: string;
    snapshotOccurredAt?: Date;
    rebuildPausedGeneration?: string;
}

export interface ProjectionAssignmentLease {
    assignmentEpoch: string;
    memberId: string;
    groupGenerationId: number;
}

export interface ProjectionAssignmentClaim {
    lease: ProjectionAssignmentLease;
    checkpoint: ProjectionCheckpointOffset;
}

/** KafkaJS 기본 3초 heartbeat보다 충분히 길고 30초 session timeout보다 짧은 stale-owner lease. */
export const PROJECTION_ASSIGNMENT_LEASE_MS = 15_000;

interface ProjectionAssignmentLeaseFilter {
    groupId: string;
    topic: string;
    partition: number;
    assignmentEpoch: string;
    assignmentMemberId: string;
    assignmentGenerationId: number;
    $expr: Record<string, unknown>;
}

export interface ProjectionRecoveryState {
    invalidRecordOffset?: string;
    lastSnapshotCompletedOffset?: string;
    lastSnapshotId?: string;
    recoverySnapshotId?: string;
}

/** 새 gap은 이전 recovery 후보를 폐기한다. */
export function latchInvalidProjectionRecord(
    state: ProjectionRecoveryState,
    recordOffset: string,
): ProjectionRecoveryState {
    if (state.invalidRecordOffset !== undefined
        && BigInt(recordOffset) <= BigInt(state.invalidRecordOffset)) return { ...state };
    return { ...state, invalidRecordOffset: recordOffset, recoverySnapshotId: undefined };
}

/** gap 뒤 서로 다른 두 full snapshot만 latch를 해제한다. */
export function observeProjectionRecoverySnapshot(
    state: ProjectionRecoveryState,
    markerOffset: string,
    snapshotId: string,
): ProjectionRecoveryState {
    const next = { ...state };
    const marker = BigInt(markerOffset);
    if (state.invalidRecordOffset !== undefined
        && marker > BigInt(state.invalidRecordOffset)
        && state.lastSnapshotId !== snapshotId) {
        if (state.recoverySnapshotId === undefined) {
            next.recoverySnapshotId = snapshotId;
        } else if (state.recoverySnapshotId !== snapshotId) {
            next.invalidRecordOffset = undefined;
            next.recoverySnapshotId = undefined;
        }
    }
    if (state.lastSnapshotCompletedOffset === undefined
        || marker >= BigInt(state.lastSnapshotCompletedOffset)) {
        next.lastSnapshotCompletedOffset = markerOffset;
        next.lastSnapshotId = snapshotId;
    }
    return next;
}

export class ProjectionCheckpointFenceError extends Error {
    constructor(topic: string, partition: number) {
        super(`Kafka projection assignment fence rejected stale owner: ${topic}[${partition}]`);
        this.name = ProjectionCheckpointFenceError.name;
    }
}

@Injectable()
export class ProjectionCheckpointRepository {
    constructor(
        @InjectModel(ProjectionCheckpoint.name)
        private readonly model: Model<ProjectionCheckpoint>,
        @InjectModel(ProjectionQuarantineRecord.name)
        private readonly quarantineModel: Model<ProjectionQuarantineRecord>,
    ) {}

    async find(groupId: string, topic: string): Promise<ProjectionCheckpointOffset[]> {
        return this.load(groupId, topic, true);
    }

    /** assignment이 없어도 dataset 일관성 판정에 사용하는 전체 durable checkpoint. */
    async findAll(groupId: string, topic: string): Promise<ProjectionCheckpointOffset[]> {
        return this.load(groupId, topic, false);
    }

    private async load(
        groupId: string,
        topic: string,
        activeLeaseOnly: boolean,
    ): Promise<ProjectionCheckpointOffset[]> {
        const checkpoints = await this.model
            .find({
                groupId,
                topic,
                ...(activeLeaseOnly ? { $expr: this.unexpiredLeaseExpression() } : {}),
            })
            .select({
                partition: 1,
                datasetGeneration: 1,
                sourceGeneration: 1,
                assignmentEpoch: 1,
                assignmentMemberId: 1,
                assignmentGenerationId: 1,
                nextOffset: 1,
                snapshotCompletedOffset: 1,
                snapshotId: 1,
                snapshotOccurredAt: 1,
                invalidRecordOffset: 1,
                rebuildPausedGeneration: 1,
                _id: 0,
            })
            .lean<Array<{
                partition: number;
                datasetGeneration?: string;
                sourceGeneration?: string;
                assignmentEpoch?: string | null;
                assignmentMemberId?: string | null;
                assignmentGenerationId?: number | null;
                nextOffset: bigint;
                snapshotCompletedOffset?: bigint | null;
                snapshotId?: string | null;
                snapshotOccurredAt?: Date | null;
                invalidRecordOffset?: bigint | null;
                rebuildPausedGeneration?: string | null;
            }>>();

        return checkpoints.map(({
            partition,
            datasetGeneration,
            sourceGeneration,
            assignmentEpoch,
            assignmentMemberId,
            assignmentGenerationId,
            nextOffset,
            snapshotCompletedOffset,
            snapshotId,
            snapshotOccurredAt,
            invalidRecordOffset,
            rebuildPausedGeneration,
        }) => ({
            partition,
            offset: nextOffset.toString(),
            ...(datasetGeneration ? { datasetGeneration } : {}),
            ...(sourceGeneration ? { sourceGeneration } : {}),
            ...(assignmentEpoch ? { assignmentEpoch } : {}),
            ...(assignmentMemberId ? { assignmentMemberId } : {}),
            ...(assignmentGenerationId === null || assignmentGenerationId === undefined
                ? {}
                : { assignmentGenerationId }),
            ...(snapshotCompletedOffset === null || snapshotCompletedOffset === undefined
                ? {}
                : { snapshotCompletedOffset: snapshotCompletedOffset.toString() }),
            ...(snapshotId ? { snapshotId } : {}),
            ...(snapshotOccurredAt ? { snapshotOccurredAt } : {}),
            ...(invalidRecordOffset === null || invalidRecordOffset === undefined
                ? {}
                : { invalidRecordOffset: invalidRecordOffset.toString() }),
            ...(rebuildPausedGeneration ? { rebuildPausedGeneration } : {}),
        }));
    }

    /**
     * 만료되었거나 명시적으로 release된 partition만 새 owner가 claim한다.
     * generation 숫자가 더 커도 다른 owner의 active lease를 선점할 수 없다.
     */
    async claimForAssignment(
        groupId: string,
        topic: string,
        partition: number,
        earliestOffset: string,
        datasetGeneration: string,
        sourceGeneration: string,
        memberId: string,
        groupGenerationId: number,
    ): Promise<ProjectionAssignmentClaim | undefined> {
        const key = { groupId, topic, partition };
        const assignmentEpoch = randomUUID();
        const lease: ProjectionAssignmentLease = { assignmentEpoch, memberId, groupGenerationId };
        const filter = {
            ...key,
            datasetGeneration,
            sourceGeneration,
            $or: [
                { assignmentEpoch: null },
                { assignmentLeaseRenewedAt: null },
                { $expr: this.expiredLeaseExpression() },
            ],
        };
        const leaseUpdate = {
            $set: {
                assignmentEpoch,
                assignmentMemberId: memberId,
                assignmentGenerationId: groupGenerationId,
            },
            $currentDate: { assignmentLeaseRenewedAt: true },
        };
        const available = await this.model.findOneAndUpdate(filter, leaseUpdate, { new: true }).lean<{
            nextOffset: bigint;
            snapshotCompletedOffset?: bigint | null;
            snapshotId?: string | null;
            snapshotOccurredAt?: Date | null;
            invalidRecordOffset?: bigint | null;
        } | null>();
        if (available) {
            return {
                lease,
                checkpoint: this.claimedCheckpoint(
                    partition,
                    datasetGeneration,
                    sourceGeneration,
                    lease,
                    available,
                ),
            };
        }

        try {
            // `$expr` filter를 upsert에 사용하지 않는다. 새 row 또는 release 직후 row만
            // 이 두 번째 CAS를 통과하며, active owner와 경합하면 unique key가 claim을 막는다.
            const inserted = await this.model.findOneAndUpdate(
                { ...key, datasetGeneration, sourceGeneration, assignmentEpoch: null },
                {
                    ...leaseUpdate,
                    $setOnInsert: {
                        nextOffset: BigInt(earliestOffset),
                    },
                },
                { upsert: true, new: true },
            ).lean<{ nextOffset: bigint } | null>();
            if (!inserted) return undefined;
            return {
                lease,
                checkpoint: this.claimedCheckpoint(
                    partition,
                    datasetGeneration,
                    sourceGeneration,
                    lease,
                    inserted,
                ),
            };
        } catch (error) {
            if (!this.isDuplicateKey(error)) throw error;
            // active owner 또는 동시 claimant가 unique stream-partition key를 이미 소유한다.
            return undefined;
        }
    }

    /** rebuild coordinator가 모든 active owner의 pause 확인 여부를 확인한다. */
    async acknowledgeRebuildPause(
        groupId: string,
        topic: string,
        partition: number,
        lease: ProjectionAssignmentLease,
        datasetGeneration: string,
    ): Promise<void> {
        const result = await this.model.updateOne(
            this.assignmentLeaseFilter(groupId, topic, partition, lease),
            { $set: { rebuildPausedGeneration: datasetGeneration } },
        );
        if (result.matchedCount !== 1) throw new ProjectionCheckpointFenceError(topic, partition);
    }

    async hasUnacknowledgedActiveAssignments(
        groupId: string,
        topic: string,
        datasetGeneration: string,
    ): Promise<boolean> {
        return (await this.model.exists({
            groupId,
            topic,
            $expr: this.unexpiredLeaseExpression(),
            rebuildPausedGeneration: { $ne: datasetGeneration },
        })) !== null;
    }

    /** 모든 consumer가 pause를 확인한 뒤 checkpoint를 새 dataset의 검증된 base로 되돌린다. */
    async resetForRebuild(
        groupId: string,
        topic: string,
        offsets: PartitionOffset[],
        datasetGeneration: string,
        sourceGeneration: string,
    ): Promise<void> {
        for (const { partition, offset } of offsets) {
            await this.model.updateOne(
                { groupId, topic, partition },
                {
                    $set: {
                        groupId,
                        topic,
                        partition,
                        datasetGeneration,
                        sourceGeneration,
                        nextOffset: BigInt(offset),
                        rebuildPausedGeneration: datasetGeneration,
                    },
                    $unset: {
                        snapshotCompletedOffset: '',
                        snapshotId: '',
                        snapshotSource: '',
                        snapshotOccurredAt: '',
                        invalidRecordOffset: '',
                        lastSnapshotCompletedOffset: '',
                        lastSnapshotId: '',
                        recoverySnapshotId: '',
                    },
                },
                { upsert: true },
            );
        }
    }

    /** 성공한 Kafka heartbeat마다 DB time lease를 연장한다. */
    async renewAssignment(
        groupId: string,
        topic: string,
        partition: number,
        lease: ProjectionAssignmentLease,
    ): Promise<void> {
        const result = await this.model.updateOne(
            this.assignmentLeaseFilter(groupId, topic, partition, lease),
            { $currentDate: { assignmentLeaseRenewedAt: true } },
        );
        if (result.matchedCount !== 1) throw new ProjectionCheckpointFenceError(topic, partition);
    }

    /** revocation/disconnect에서 exact owner만 lease를 명시적으로 반환한다. */
    async releaseAssignment(
        groupId: string,
        topic: string,
        partition: number,
        lease: ProjectionAssignmentLease,
    ): Promise<boolean> {
        const result = await this.model.updateOne(
            {
                groupId,
                topic,
                partition,
                assignmentEpoch: lease.assignmentEpoch,
                assignmentMemberId: lease.memberId,
                assignmentGenerationId: lease.groupGenerationId,
            },
            {
                $unset: {
                    assignmentEpoch: '',
                    assignmentMemberId: '',
                    assignmentGenerationId: '',
                    assignmentLeaseRenewedAt: '',
                },
            },
        );
        return result.matchedCount === 1;
    }

    async advance(
        groupId: string,
        topic: string,
        partition: number,
        lease: ProjectionAssignmentLease,
        nextOffset: string,
        snapshotBarrier?: SnapshotBarrierReceipt,
    ): Promise<void> {
        const key = this.assignmentLeaseFilter(groupId, topic, partition, lease);
        const value = BigInt(nextOffset);
        const update = snapshotBarrier
            ? await this.snapshotAdvanceUpdate(key, value, snapshotBarrier)
            : { $max: { nextOffset: value } };
        const result = await this.model.updateOne(key, update);
        if (result.matchedCount !== 1) throw new ProjectionCheckpointFenceError(topic, partition);
    }

    async markInvalidRecord(
        groupId: string,
        topic: string,
        partition: number,
        lease: ProjectionAssignmentLease,
        recordOffset: string,
    ): Promise<void> {
        const key = this.assignmentLeaseFilter(groupId, topic, partition, lease);
        const current = await this.loadRecoveryState(key, topic, partition);
        const next = latchInvalidProjectionRecord(current, recordOffset);
        const update: Record<string, unknown> = {
            $set: { invalidRecordOffset: BigInt(next.invalidRecordOffset as string) },
        };
        if (next.recoverySnapshotId === undefined) {
            update.$unset = { recoverySnapshotId: '' };
        }
        const result = await this.model.updateOne(key, update);
        if (result.matchedCount !== 1) throw new ProjectionCheckpointFenceError(topic, partition);
    }

    /** 격리 원문이 durable하게 저장된 뒤에만 호출자가 checkpoint를 전진시킨다. */
    async quarantine(
        groupId: string,
        topic: string,
        partition: number,
        offset: string,
        eventKey: string | null,
        payload: string | null,
        reason: string,
    ): Promise<void> {
        const key = { groupId, topic, partition, messageOffset: BigInt(offset) };
        const record = { ...key, eventKey, payload, reason };
        try {
            await this.quarantineModel.updateOne(key, { $setOnInsert: record }, { upsert: true });
        } catch (error) {
            if (!this.isDuplicateKey(error)) throw error;
            await this.quarantineModel.updateOne(key, { $setOnInsert: record });
        }
    }

    private isDuplicateKey(error: unknown): boolean {
        return typeof error === 'object'
            && error !== null
            && 'code' in error
            && error.code === 11000;
    }

    private claimedCheckpoint(
        partition: number,
        datasetGeneration: string,
        sourceGeneration: string,
        lease: ProjectionAssignmentLease,
        checkpoint: {
            nextOffset: bigint;
            snapshotCompletedOffset?: bigint | null;
            snapshotId?: string | null;
            snapshotOccurredAt?: Date | null;
            invalidRecordOffset?: bigint | null;
        },
    ): ProjectionCheckpointOffset {
        return {
            partition,
            offset: checkpoint.nextOffset.toString(),
            datasetGeneration,
            sourceGeneration,
            assignmentEpoch: lease.assignmentEpoch,
            assignmentMemberId: lease.memberId,
            assignmentGenerationId: lease.groupGenerationId,
            ...(checkpoint.snapshotCompletedOffset === null || checkpoint.snapshotCompletedOffset === undefined
                ? {}
                : { snapshotCompletedOffset: checkpoint.snapshotCompletedOffset.toString() }),
            ...(checkpoint.snapshotId ? { snapshotId: checkpoint.snapshotId } : {}),
            ...(checkpoint.snapshotOccurredAt ? { snapshotOccurredAt: checkpoint.snapshotOccurredAt } : {}),
            ...(checkpoint.invalidRecordOffset === null || checkpoint.invalidRecordOffset === undefined
                ? {}
                : { invalidRecordOffset: checkpoint.invalidRecordOffset.toString() }),
        };
    }

    private async snapshotAdvanceUpdate(
        key: ProjectionAssignmentLeaseFilter,
        nextOffset: bigint,
        snapshotBarrier: SnapshotBarrierReceipt,
    ): Promise<Record<string, unknown>> {
        const current = await this.loadRecoveryState(key, key.topic, key.partition);
        const next = observeProjectionRecoverySnapshot(
            current,
            snapshotBarrier.offset,
            snapshotBarrier.snapshotId,
        );
        const set: Record<string, unknown> = {
            snapshotId: snapshotBarrier.snapshotId,
            snapshotSource: snapshotBarrier.source,
            snapshotOccurredAt: snapshotBarrier.occurredAt,
            lastSnapshotCompletedOffset: BigInt(next.lastSnapshotCompletedOffset as string),
            lastSnapshotId: next.lastSnapshotId,
        };
        const unset: Record<string, ''> = {};
        if (next.invalidRecordOffset === undefined) unset.invalidRecordOffset = '';
        else set.invalidRecordOffset = BigInt(next.invalidRecordOffset);
        if (next.recoverySnapshotId === undefined) unset.recoverySnapshotId = '';
        else set.recoverySnapshotId = next.recoverySnapshotId;
        return {
            $max: {
                nextOffset,
                snapshotCompletedOffset: BigInt(snapshotBarrier.offset),
            },
            $set: set,
            ...(Object.keys(unset).length > 0 ? { $unset: unset } : {}),
        };
    }

    private async loadRecoveryState(
        key: ProjectionAssignmentLeaseFilter,
        topic: string,
        partition: number,
    ): Promise<ProjectionRecoveryState> {
        const checkpoint = await this.model.findOne(key, {
            invalidRecordOffset: 1,
            lastSnapshotCompletedOffset: 1,
            lastSnapshotId: 1,
            recoverySnapshotId: 1,
            _id: 0,
        }).lean<{
            invalidRecordOffset?: bigint | null;
            lastSnapshotCompletedOffset?: bigint | null;
            lastSnapshotId?: string | null;
            recoverySnapshotId?: string | null;
        } | null>();
        if (!checkpoint) throw new ProjectionCheckpointFenceError(topic, partition);
        return {
            ...(checkpoint.invalidRecordOffset === null || checkpoint.invalidRecordOffset === undefined
                ? {}
                : { invalidRecordOffset: checkpoint.invalidRecordOffset.toString() }),
            ...(checkpoint.lastSnapshotCompletedOffset === null
                || checkpoint.lastSnapshotCompletedOffset === undefined
                ? {}
                : { lastSnapshotCompletedOffset: checkpoint.lastSnapshotCompletedOffset.toString() }),
            ...(checkpoint.lastSnapshotId ? { lastSnapshotId: checkpoint.lastSnapshotId } : {}),
            ...(checkpoint.recoverySnapshotId
                ? { recoverySnapshotId: checkpoint.recoverySnapshotId }
                : {}),
        };
    }

    private assignmentLeaseFilter(
        groupId: string,
        topic: string,
        partition: number,
        lease: ProjectionAssignmentLease,
    ): ProjectionAssignmentLeaseFilter {
        return {
            groupId,
            topic,
            partition,
            assignmentEpoch: lease.assignmentEpoch,
            assignmentMemberId: lease.memberId,
            assignmentGenerationId: lease.groupGenerationId,
            $expr: this.unexpiredLeaseExpression(),
        };
    }

    private expiredLeaseExpression(): Record<string, unknown> {
        return {
            $lte: ['$assignmentLeaseRenewedAt', this.leaseCutoffExpression()],
        };
    }

    private unexpiredLeaseExpression(): Record<string, unknown> {
        return {
            $gt: ['$assignmentLeaseRenewedAt', this.leaseCutoffExpression()],
        };
    }

    private leaseCutoffExpression(): Record<string, unknown> {
        return {
            $dateSubtract: {
                startDate: '$$NOW',
                unit: 'millisecond',
                amount: PROJECTION_ASSIGNMENT_LEASE_MS,
            },
        };
    }
}
