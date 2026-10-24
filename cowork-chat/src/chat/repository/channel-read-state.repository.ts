import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, Types} from 'mongoose';
import {ChannelReadState} from '../schema';

@Injectable()
export class ChannelReadStateRepository {
    constructor(@InjectModel(ChannelReadState.name) private readonly model: Model<ChannelReadState>) {}

    async updateLastRead(channelId: number, userId: number, messageId: Types.ObjectId): Promise<void> {
        await this.model.updateOne(
            {channelId, userId},
            {$max: {lastReadMessageId: messageId}},
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
        return channelIds.map(channelId => ({channelId, lastReadMessageId: cursors.get(channelId) ?? null}));
    }
}
