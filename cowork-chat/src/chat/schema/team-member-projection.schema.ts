import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
`team.member.event`로 동기화되는 팀 멤버십 읽기 모델.
*/
@schema({timestamps: true, versionKey: false})
export class TeamMemberProjection {
	@prop({required: true}) teamId!: number;
	@prop({required: true}) userId!: number;
	@prop({required: true, default: ''}) role!: string;
	@prop({required: true, default: ''}) teamName!: string;
	@prop({type: String, default: null}) nickname!: Nullable<string>;
	// 이 필드가 생기기 전에 받은 멤버와 탈퇴한 멤버는 null이다.
	@prop({type: Date, default: null}) joinedAt!: Nullable<Date>;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
	@prop({required: true, type: BigInt}) authorizationVersion!: bigint;
}

export const teamMemberProjectionSchema = SchemaFactory.createForClass(TeamMemberProjection)
	.index({teamId: 1, userId: 1}, {unique: true})
	.index({userId: 1, teamId: 1});
