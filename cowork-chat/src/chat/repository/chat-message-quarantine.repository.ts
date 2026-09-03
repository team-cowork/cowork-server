import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import {
    ChatMessageQuarantineErrorType,
    ChatMessageQuarantineRecord,
    ChatMessageQuarantineStatus,
} from '../schema/chat-message-quarantine.schema';

export const CHAT_MESSAGE_QUARANTINE_MAX_PAYLOAD_BYTES = 65_536;

export type QuarantineInput = {
    groupId: string;
    topic: string;
    partition: number;
    messageOffset: string;
    eventKey: string | null;
    payload: string | null;
    contractVersion: number;
    errorType: ChatMessageQuarantineErrorType;
    reasonCode: string;
    reason: string;
    retentionDays: number;
};

@Injectable()
export class ChatMessageQuarantineRepository {
    constructor(@InjectModel(ChatMessageQuarantineRecord.name) private readonly model: Model<ChatMessageQuarantineRecord>) {}

    /** 위치 unique key로 멱등 upsert하며 최초 raw payload는 재전달로 덮어쓰지 않는다. */
    async quarantine(input: QuarantineInput): Promise<void> {
        const now = new Date();
        const raw = truncateUtf8(input.payload, CHAT_MESSAGE_QUARANTINE_MAX_PAYLOAD_BYTES);
        await this.model.updateOne(
            {
                groupId: input.groupId, topic: input.topic, partition: input.partition, messageOffset: input.messageOffset,
            },
            {
                $setOnInsert: {
                    eventKey: input.eventKey, payload: raw.value, payloadTruncated: raw.truncated, payloadBytes: raw.bytes,
                    contractVersion: input.contractVersion, errorType: input.errorType, reasonCode: input.reasonCode,
                    reason: input.reason, status: 'QUARANTINED', firstObservedAt: now,
                    expiresAt: new Date(now.getTime() + input.retentionDays * 24 * 60 * 60 * 1_000),
                },
                $set: { lastObservedAt: now },
            },
            { upsert: true },
        );
    }

    findById(id: string): Promise<ChatMessageQuarantineRecord | null> {
        return this.model.findById(id).lean<ChatMessageQuarantineRecord>();
    }

    async requestReprocess(id: string): Promise<boolean> {
        const result = await this.model.updateOne(
            { _id: id, status: 'QUARANTINED', payloadTruncated: false, payload: { $type: 'string' } },
            { $set: { status: 'REPROCESS_REQUESTED', reprocessRequestedAt: new Date(), lastReprocessError: null } },
        );
        return result.modifiedCount === 1;
    }

    async discard(id: string): Promise<boolean> {
        const result = await this.model.updateOne(
            { _id: id, status: { $in: ['QUARANTINED', 'REPROCESS_REQUESTED'] } },
            { $set: { status: 'DISCARDED', discardedAt: new Date() }, $unset: { payload: 1, eventKey: 1 } },
        );
        return result.modifiedCount === 1;
    }

    claimReprocess(): Promise<ChatMessageQuarantineRecord | null> {
        return this.model.findOneAndUpdate(
            { status: 'REPROCESS_REQUESTED', payloadTruncated: false, payload: { $type: 'string' } },
            { $set: { status: 'PROCESSING', processingStartedAt: new Date() } },
            { sort: { reprocessRequestedAt: 1 }, new: true },
        ).lean<ChatMessageQuarantineRecord>();
    }

    async markReprocessed(id: string): Promise<void> {
        await this.model.updateOne(
            { _id: id, status: 'PROCESSING' },
            { $set: { status: 'REPROCESSED', processedAt: new Date() }, $unset: { payload: 1, eventKey: 1 } },
        );
    }

    async releaseReprocess(id: string, error: string): Promise<void> {
        await this.model.updateOne(
            { _id: id, status: 'PROCESSING' },
            { $set: { status: 'QUARANTINED', lastReprocessError: error, processingStartedAt: null } },
        );
    }

    async reclaimStaleProcessing(thresholdMs: number): Promise<number> {
        const result = await this.model.updateMany(
            { status: 'PROCESSING', processingStartedAt: { $lt: new Date(Date.now() - thresholdMs) } },
            { $set: { status: 'QUARANTINED', processingStartedAt: null, lastReprocessError: 'processing claim timed out' } },
        );
        return result.modifiedCount;
    }

    async summary(): Promise<Array<{ status: ChatMessageQuarantineStatus; errorType: ChatMessageQuarantineErrorType; reasonCode: string; count: number }>> {
        return this.model.aggregate([
            { $group: { _id: { status: '$status', errorType: '$errorType', reasonCode: '$reasonCode' }, count: { $sum: 1 } } },
            { $project: { _id: 0, status: '$_id.status', errorType: '$_id.errorType', reasonCode: '$_id.reasonCode', count: 1 } },
        ]);
    }
}

export function truncateUtf8(payload: string | null, maximumBytes: number): { value: string | null; bytes: number; truncated: boolean } {
    if (payload === null) return { value: null, bytes: 0, truncated: false };
    const bytes = Buffer.byteLength(payload, 'utf8');
    if (bytes <= maximumBytes) return { value: payload, bytes, truncated: false };
    let end = maximumBytes;
    while (end > 0 && (Buffer.from(payload, 'utf8')[end] & 0b1100_0000) === 0b1000_0000) end -= 1;
    return { value: Buffer.from(payload, 'utf8').subarray(0, end).toString('utf8'), bytes, truncated: true };
}
