import 'dotenv/config';
import mongoose, { Types } from 'mongoose';
import { Message, MessageSchema } from '../chat/schema/message.schema';
import { ChannelProjection, ChannelProjectionSchema } from '../chat/schema/channel-projection.schema';

const BATCH_SIZE = 500;
type Category = 'CHANNEL_MISSING_OR_DELETED' | 'CHANNEL_SCOPE_INVALID' | 'MESSAGE_SCOPE_MISMATCH'
    | 'PARENT_INVALID_ID' | 'PARENT_MISSING' | 'PARENT_CROSS_CHANNEL';
type AuditMessage = {
    _id: Types.ObjectId;
    channelId: number;
    teamId: number | null;
    projectId: number | null;
    parentMessageId: Types.ObjectId | string | null;
};
type AuditChannel = {
    channelId: number;
    teamId: number | null;
    projectId: number | null;
    type: string;
    deleted: boolean;
};
type AuditParent = { _id: Types.ObjectId; channelId: number };

async function main(): Promise<void> {
    const uri = process.env.MONGODB_URI;
    if (!uri) {
        throw new Error('MONGODB_URI is required');
    }

    if (process.argv.length !== 2) {
        throw new Error('usage: message-scope-audit');
    }

    await mongoose.connect(uri, { autoCreate: false, autoIndex: false });
    const messages = (mongoose.models[Message.name]
        ?? mongoose.model(Message.name, MessageSchema)) as mongoose.Model<Message>;
    const channels = (mongoose.models[ChannelProjection.name]
        ?? mongoose.model(ChannelProjection.name, ChannelProjectionSchema)) as mongoose.Model<ChannelProjection>;
    const counts = new Map<Category, number>();
    let cursor: Types.ObjectId | undefined;
    let scanned = 0;

    while (true) {
        const batch = await messages.find(cursor ? { _id: { $gt: cursor } } : {})
            .sort({ _id: 1 }).limit(BATCH_SIZE)
            .select('_id channelId teamId projectId parentMessageId')
            .lean<AuditMessage[]>();
        if (batch.length === 0) {
            break;
        }

        cursor = batch.at(-1)!._id;
        scanned += batch.length;

        const channelRows = await channels.find({ channelId: { $in: [...new Set(batch.map(m => m.channelId))] } })
            .select('channelId teamId projectId type deleted').lean<AuditChannel[]>();
        const channelMap = new Map(channelRows.map(channel => [channel.channelId, channel]));
        const parentIds = batch.filter(m => m.parentMessageId != null && m.parentMessageId !== '' && Types.ObjectId.isValid(m.parentMessageId))
            .map(m => new Types.ObjectId(m.parentMessageId!));
        const parentRows = parentIds.length === 0
            ? []
            : await messages.find({ _id: { $in: parentIds } })
                .select('_id channelId').lean<AuditParent[]>();
        const parentMap = new Map(parentRows.map(parent => [parent._id.toString(), parent]));

        for (const message of batch) {
            const id = message._id.toString();
            const report = (category: Category, extra: Record<string, unknown> = {}) => {
                counts.set(category, (counts.get(category) ?? 0) + 1);
                console.log(JSON.stringify({
                    category, messageId: id, channelId: message.channelId, ...extra,
                }));
            };

            const channel = channelMap.get(message.channelId);
            if (!channel || channel.deleted) {
                report('CHANNEL_MISSING_OR_DELETED');
            } else if ((channel.type === 'DM' && ((channel.teamId ?? null) !== null || (channel.projectId ?? null) !== null))
                || (channel.type !== 'DM' && (channel.teamId ?? null) === null)) {
                report('CHANNEL_SCOPE_INVALID');
            } else if ((message.teamId ?? null) !== (channel.teamId ?? null)
                || (message.projectId ?? null) !== (channel.projectId ?? null)) {
                report('MESSAGE_SCOPE_MISMATCH', {
                    currentTeamId: message.teamId ?? null, currentProjectId: message.projectId ?? null,
                    expectedTeamId: channel.teamId, expectedProjectId: channel.projectId,
                });
            }

            if (message.parentMessageId == null || message.parentMessageId === '') {
                continue;
            }

            if (!Types.ObjectId.isValid(message.parentMessageId)) {
                report('PARENT_INVALID_ID');
                continue;
            }

            const parent = parentMap.get(message.parentMessageId.toString());
            if (!parent) {
                report('PARENT_MISSING');
            } else if (parent.channelId !== message.channelId) {
                report('PARENT_CROSS_CHANNEL', { parentMessageId: parent._id.toString() });
            }
        }
    }

    console.log(JSON.stringify({ summary: true, scanned, counts: Object.fromEntries(counts) }));
}

void main()
    .catch((error: unknown) => {
        console.error(error instanceof Error ? error.message : error);
        process.exitCode = 1;
    })
    .finally(async () => {
        await mongoose.disconnect();
    });
