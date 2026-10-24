import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';

/**
Effective permissions published by Preference; this is not a project membership.
*/
@Schema({timestamps: true, versionKey: false})
export class ProjectPermissionProjection {
    @Prop({required: true}) projectId!: number;
    @Prop({required: true}) userId!: number;
    @Prop({required: true, default: false}) read!: boolean;
    @Prop({required: true, default: false}) write!: boolean;
    @Prop({required: true, type: Date}) sourceOccurredAt!: Date;
    @Prop({required: true}) sourceVersion!: bigint;
    // eslint-disable-next-line no-warning-comments -- Tracked compatibility cleanup with a deployment-dependent deadline.
    // TODO(#596): 2026-11-01 05:00 KST 이후 구형 Kafka·Mongo stamp 보정과 rollback 종료 확인 후 default 옵션만 제거.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    // 같은 sourceVersion의 stamp 누락은 snapshot만으로 복구되지 않아 시작 시 자동 재구축하며, 완료 후 보정을 확인한다.
    @Prop({required: true, default: 0n}) sourceMembershipVersion!: bigint;
}

export const ProjectPermissionProjectionSchema = SchemaFactory.createForClass(ProjectPermissionProjection);
ProjectPermissionProjectionSchema.index({projectId: 1, userId: 1}, {unique: true});
