import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
`channel.event.v2`로 동기화되는 채널 읽기 모델.
*/
@schema({timestamps: true, versionKey: false})
export class ChannelProjection {
	@prop({required: true}) channelId!: number;
	@prop({type: Number, default: null}) teamId!: Nullable<number>;
	@prop({type: Number, default: null}) projectId!: Nullable<number>;
	@prop({required: true, default: ''}) name!: string;
	@prop({required: true, default: ''}) type!: string;
	@prop({required: true, default: ''}) viewType!: string;
	@prop({type: String, default: null}) description!: Nullable<string>;
	@prop({required: true, default: false}) isPrivate!: boolean;
	@prop({required: true, default: 0}) position!: number;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const channelProjectionSchema = SchemaFactory.createForClass(ChannelProjection)
	.index({channelId: 1}, {unique: true})
	.index({teamId: 1, position: 1, channelId: 1})
	.index({projectId: 1, deleted: 1, channelId: 1});
