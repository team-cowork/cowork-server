import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, Types} from 'mongoose';
import {ChannelReadState} from '../schema';

@Injectable()
export class ChannelReadStateRepository {
    constructor(@InjectModel(ChannelReadState.name) private readonly model: Model<ChannelReadState>) {}

    /**
     읽음 위치를 `$max`로 전진시키고, 갱신 뒤 DB에 남은 위치를 반환한다.
     다른 기기의 늦은 요청이 더 오래된 ID를 보내면 반환값은 요청값이 아니라 이미 채택된 더 최신 위치다.
     */
    async updateLastRead(channelId: number, userId: number, messageId: Types.ObjectId): Promise<Types.ObjectId> {
        const state = await this.model.findOneAndUpdate(
            {channelId, userId},
            {$max: {lastReadMessageId: messageId}},
            {upsert: true, returnDocument: 'after', projection: {lastReadMessageId: 1}},
        ).lean();
        return state?.lastReadMessageId ?? messageId;
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
