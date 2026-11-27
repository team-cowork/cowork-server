import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
Preference가 팀 멤버별로 발행한 관리자 권한 판정.
*/
@schema({timestamps: true, versionKey: false})
export class TeamAccessProjection {
	@prop({required: true}) userId!: number;
	@prop({required: true}) teamId!: number;
	@prop({required: true}) administrator!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
	@prop({required: true}) sourceMembershipVersion!: bigint;
	@prop({type: BigInt}) sourceAuthorizationVersion!: Nullable<bigint>;
}

export const teamAccessProjectionSchema = SchemaFactory.createForClass(TeamAccessProjection)
	.index({teamId: 1, userId: 1}, {unique: true});
