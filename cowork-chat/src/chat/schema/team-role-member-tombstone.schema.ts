import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

/**
이전 assignment 이벤트의 부활을 막는 member 단위 tombstone.
*/
@schema({timestamps: true, versionKey: false})
export class TeamRoleMemberTombstone {
	@prop({required: true}) teamId!: number;
	@prop({required: true}) accountId!: number;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const teamRoleMemberTombstoneSchema = SchemaFactory.createForClass(TeamRoleMemberTombstone)
	.index({teamId: 1, accountId: 1}, {unique: true});
