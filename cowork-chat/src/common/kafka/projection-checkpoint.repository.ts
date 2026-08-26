import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { PartitionOffset } from 'kafkajs';
import { ProjectionCheckpoint } from './projection-checkpoint.schema';
import { ProjectionQuarantineRecord } from './projection-quarantine.schema';

export interface SnapshotBarrierReceipt {
    offset: string;
    snapshotId: string;
    source: string;
    occurredAt: Date;
}

export interface ProjectionCheckpointOffset extends PartitionOffset {
    snapshotCompletedOffset?: string;
    snapshotId?: string;
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
        const checkpoints = await this.model
            .find({ groupId, topic })
            .select({ partition: 1, nextOffset: 1, snapshotCompletedOffset: 1, snapshotId: 1, _id: 0 })
            .lean<Array<{
                partition: number;
                nextOffset: bigint;
                snapshotCompletedOffset?: bigint | null;
                snapshotId?: string | null;
            }>>();

        return checkpoints.map(({ partition, nextOffset, snapshotCompletedOffset, snapshotId }) => ({
            partition,
            offset: nextOffset.toString(),
            ...(snapshotCompletedOffset === null || snapshotCompletedOffset === undefined
                ? {}
                : { snapshotCompletedOffset: snapshotCompletedOffset.toString() }),
            ...(snapshotId ? { snapshotId } : {}),
        }));
    }

    async advance(
        groupId: string,
        topic: string,
        partition: number,
        nextOffset: string,
        snapshotBarrier?: SnapshotBarrierReceipt,
    ): Promise<void> {
        const key = { groupId, topic, partition };
        const value = BigInt(nextOffset);
        const update = {
            $setOnInsert: key,
            $max: {
                nextOffset: value,
                ...(snapshotBarrier ? { snapshotCompletedOffset: BigInt(snapshotBarrier.offset) } : {}),
            },
            ...(snapshotBarrier ? {
                $set: {
                    snapshotId: snapshotBarrier.snapshotId,
                    snapshotSource: snapshotBarrier.source,
                    snapshotOccurredAt: snapshotBarrier.occurredAt,
                },
            } : {}),
        };
        try {
            await this.model.updateOne(key, update, { upsert: true });
        } catch (error) {
            // rebalance 경계에서 두 replica가 같은 checkpoint를 최초 생성하는 upsert race를 흡수한다.
            if (!this.isDuplicateKey(error)) throw error;
            await this.model.updateOne(key, {
                $max: update.$max,
                ...('$set' in update ? { $set: update.$set } : {}),
            });
        }
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
}
