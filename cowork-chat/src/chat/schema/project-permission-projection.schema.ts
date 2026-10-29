import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
Effective permissions published by Preference; this is not a project membership.
*/
@schema({timestamps: true, versionKey: false})
export class ProjectPermissionProjection {
	@prop({required: true}) projectId!: number;
	@prop({required: true}) userId!: number;
	@prop({required: true, default: false}) read!: boolean;
	@prop({required: true, default: false}) write!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
	// 호환 정리(#596): 2026-11-01 05:00 KST 이후 구형 Kafka·Mongo stamp 보정과 rollback 종료 확인 후 default 옵션만 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	// 같은 sourceVersion의 stamp 누락은 snapshot만으로 복구되지 않아 시작 시 자동 재구축하며, 완료 후 보정을 확인한다.
	@prop({required: true, default: 0n}) sourceMembershipVersion!: bigint;
	// 호환 정리(#596): 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 nullable fallback 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	@prop({type: BigInt, default: null}) sourceAuthorizationVersion!: Nullable<bigint>;
}

export const projectPermissionProjectionSchema = SchemaFactory.createForClass(ProjectPermissionProjection)
	.index({projectId: 1, userId: 1}, {unique: true});
