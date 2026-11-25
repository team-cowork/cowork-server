import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
`preference.channel-access.state.v1`로 받은 채널별 사용자 권한 판정 결과.
`allowedPermissions`에 없는 채널 권한은 거부다.
*/
@schema({timestamps: true, versionKey: false})
export class ChannelAccessProjection {
	@prop({required: true}) channelId!: number;
	@prop({required: true}) userId!: number;
	@prop({required: true}) teamId!: number;
	@prop({type: [String], required: true, default: []}) allowedPermissions!: string[];
	@prop({required: true, default: false}) readPrivate!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
	@prop({required: true}) sourceMembershipVersion!: bigint;
	@prop({type: BigInt}) sourceAuthorizationVersion!: Nullable<bigint>;
}

export const channelAccessProjectionSchema = SchemaFactory.createForClass(ChannelAccessProjection)
	.index({channelId: 1, userId: 1}, {unique: true});
