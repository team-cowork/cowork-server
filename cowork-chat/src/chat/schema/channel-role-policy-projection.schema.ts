import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
`preference.channel-role-policy.state.v2`의 role×channel 권한 덮어쓰기 projection.
`permissions`에 없는 키는 역할 기본 권한을 따른다.
*/
@schema({timestamps: true, versionKey: false})
export class ChannelRolePolicyProjection {
	@prop({required: true}) teamId!: number;
	@prop({required: true}) channelId!: number;
	@prop({required: true}) roleId!: number;
	@prop({type: Object, default: null}) permissions!: Nullable<Record<string, boolean>>;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const channelRolePolicyProjectionSchema = SchemaFactory.createForClass(ChannelRolePolicyProjection)
	.index({teamId: 1, channelId: 1, roleId: 1}, {unique: true})
	.index({roleId: 1, channelId: 1, deleted: 1});
