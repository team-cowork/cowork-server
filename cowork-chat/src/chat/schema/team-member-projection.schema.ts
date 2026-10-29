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
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
	// @todo #596: 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 nullable fallback 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	@prop({type: BigInt, default: null}) authorizationVersion!: Nullable<bigint>;
}

export const teamMemberProjectionSchema = SchemaFactory.createForClass(TeamMemberProjection)
	.index({teamId: 1, userId: 1}, {unique: true})
	.index({userId: 1, teamId: 1});
