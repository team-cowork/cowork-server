import 'dotenv/config';
import mongoose from 'mongoose';
import {
    ChatMessageQuarantineRecord,
    ChatMessageQuarantineRecordSchema,
} from '../chat/schema/chat-message-quarantine.schema';

const [command, id] = process.argv.slice(2);
const uri = process.env.MONGODB_URI;

type QuarantineSummaryRow = {
    _id: { status: string; errorType: string; reasonCode: string };
    count: number;
    lastOffset: string;
};

async function main(): Promise<void> {
    if (!uri) throw new Error('MONGODB_URI is required');
    if (!command || !['list', 'show', 'reprocess', 'discard'].includes(command)) {
        throw new Error('usage: chat-message-quarantine <list|show|reprocess|discard> [recordId]');
    }
    await mongoose.connect(uri);
    const model = (mongoose.models[ChatMessageQuarantineRecord.name]
        ?? mongoose.model(ChatMessageQuarantineRecord.name, ChatMessageQuarantineRecordSchema)) as mongoose.Model<ChatMessageQuarantineRecord>;
    if (command === 'list') {
        const rows = await model.aggregate<QuarantineSummaryRow>([
            { $group: { _id: { status: '$status', errorType: '$errorType', reasonCode: '$reasonCode' }, count: { $sum: 1 }, lastOffset: { $max: '$messageOffset' } } },
            { $sort: { '_id.status': 1, '_id.errorType': 1, '_id.reasonCode': 1 } },
        ]);
        // eslint-disable-next-line no-console -- the CLI's purpose is to present the operator summary.
        console.table(rows.map((row) => ({ ...row._id, count: row.count, lastOffset: row.lastOffset })));
        return;
    }
    if (!id) throw new Error(`recordId is required for ${command}`);
    if (command === 'show') {
        // This command intentionally exposes retained raw payload only to an operator with MongoDB access.
        const record = await model.findById(id).lean<ChatMessageQuarantineRecord>();
        if (!record) throw new Error('quarantine record not found');
        // eslint-disable-next-line no-console -- this explicit command is the restricted raw-payload inspection path.
        console.warn('WARNING: payload can contain message content and attachment URLs. Do not copy it to tickets or logs.');
        // eslint-disable-next-line no-console -- this explicit command is the restricted raw-payload inspection path.
        console.dir(record, { depth: null });
        return;
    }
    const result = command === 'reprocess'
        ? await model.updateOne(
            { _id: id, status: 'QUARANTINED', payloadTruncated: false, payload: { $type: 'string' as const } },
            { $set: { status: 'REPROCESS_REQUESTED', reprocessRequestedAt: new Date(), lastReprocessError: null } },
        )
        : await model.updateOne(
            { _id: id, status: { $in: ['QUARANTINED', 'REPROCESS_REQUESTED'] as const } },
            { $set: { status: 'DISCARDED', discardedAt: new Date() }, $unset: { payload: 1, eventKey: 1 } },
        );
    if (result.modifiedCount !== 1) throw new Error(`record cannot be ${command}ed in its current state`);
    // eslint-disable-next-line no-console -- the CLI confirms the requested state transition to its operator.
    console.log(`record ${id} ${command} request completed`);
}

void main().finally(() => mongoose.disconnect());
