import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';

/**
`team.member.event`로 동기화되는 팀 멤버십 읽기 모델.
*/
@Schema({timestamps: true, versionKey: false})
export class TeamMemberProjection {
    @Prop({required: true}) teamId!: number;
    @Prop({required: true}) userId!: number;
    @Prop({required: true, default: ''}) role!: string;
    @Prop({required: true, default: ''}) teamName!: string;
    @Prop({type: String, default: null}) nickname!: string | null;
    @Prop({required: true, default: false}) deleted!: boolean;
    @Prop({required: true, type: Date}) sourceOccurredAt!: Date;
    @Prop({required: true}) sourceVersion!: bigint;
    // eslint-disable-next-line no-warning-comments -- Tracked compatibility cleanup with a deployment-dependent deadline.
    // TODO(#596): 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 nullable fallback 제거.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    @Prop({type: BigInt, default: null}) authorizationVersion!: bigint | null;
}

export const TeamMemberProjectionSchema = SchemaFactory.createForClass(TeamMemberProjection);
TeamMemberProjectionSchema.index({teamId: 1, userId: 1}, {unique: true});
TeamMemberProjectionSchema.index({userId: 1, teamId: 1});
