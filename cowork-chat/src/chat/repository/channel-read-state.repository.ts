import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, Types} from 'mongoose';
import {ChannelReadState} from '../schema';

@Injectable()
export class ChannelReadStateRepository {
    constructor(@InjectModel(ChannelReadState.name) private readonly model: Model<ChannelReadState>) {}

    async updateLastRead(channelId: number, userId: number, messageId: Types.ObjectId): Promise<void> {
        const updated = await this.model.updateOne({channelId, userId}, {$max: {lastReadMessageId: messageId}});
        if (updated.matchedCount > 0) {
            return;
        }

        const legacy = await this.model.db.collection<{lastReadMessageId?: Types.ObjectId | null}>('channelmembers')
            .findOne({channelId, userId}, {projection: {lastReadMessageId: 1}});
        const legacyId = legacy?.lastReadMessageId;
        const latestId = legacyId && legacyId.toHexString() > messageId.toHexString() ? legacyId : messageId;
        await this.model.updateOne(
            {channelId, userId},
            {$max: {lastReadMessageId: latestId}},
            {upsert: true},
        );
    }

    async findForChannels(channelIds: number[], userId: number): Promise<Array<{
        channelId: number;
        lastReadMessageId: Types.ObjectId | null;
    }>> {
        if (channelIds.length === 0) {
            return [];
        }

        const states = await this.model.find({channelId: {$in: channelIds}, userId}).lean();
        const cursors = new Map(states.map(state => [state.channelId, state.lastReadMessageId]));
        const missingIds = channelIds.filter(id => !cursors.has(id));
        // Read legacy cursor values only; these documents never authorize access.
        if (missingIds.length > 0) {
            const legacy = await this.model.db.collection<{
                channelId: number;
                userId: number;
                lastReadMessageId?: Types.ObjectId | null;
            }>('channelmembers').find(
                {channelId: {$in: missingIds}, userId},
                {projection: {channelId: 1, lastReadMessageId: 1}},
            ).toArray();
            for (const state of legacy) {
                if (state.lastReadMessageId) {
                    cursors.set(state.channelId, state.lastReadMessageId);
                }
            }
        }

        return channelIds.map(channelId => ({channelId, lastReadMessageId: cursors.get(channelId) ?? null}));
    }
}
