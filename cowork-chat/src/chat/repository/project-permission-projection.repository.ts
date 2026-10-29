import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ProjectPermissionProjection} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {projectionSourceVersion, PROJECTION_EPOCH} from './versioned-projection.util.js';

@injectable()
export class ProjectPermissionProjectionRepository {
	constructor(@injectModel(ProjectPermissionProjection.name) private readonly model: Model<ProjectPermissionProjection>) {}

	async canRead(projectId: number, userId: number, authorizationVersion: bigint | mongo.Long): Promise<boolean> {
		const sourceAuthorizationVersion = typeof authorizationVersion === 'bigint' ? mongo.Long.fromBigInt(authorizationVersion) : authorizationVersion;
		return sourceAuthorizationVersion.greaterThan(mongo.Long.ZERO)
			&& (await this.model.exists({
				projectId,
				userId,
				read: true,
				// @todo #596: 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 membership 대체 제거.
				// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
				$expr: {$eq: [{$ifNull: ['$sourceAuthorizationVersion', '$sourceMembershipVersion']}, sourceAuthorizationVersion]},
			})) !== null;
	}

	async upsert(
		grant: {
			projectId: number;
			userId: number;
			canRead: boolean;
			canWrite: boolean;
			occurredAt: Date;
			sourceVersion: mongo.Long;
			// @todo #596: 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo stamp 보정과 rollback 종료 확인 후 이 인자 기본값만 제거.
			// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
			sourceMembershipVersion?: mongo.Long;
			// @todo #596: 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 null 기본값 제거.
			// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
			sourceAuthorizationVersion?: Nullable<mongo.Long>;
		},

	): Promise<void> {
		const {projectId, userId, canRead, canWrite, occurredAt, sourceVersion, sourceMembershipVersion = mongo.Long.ZERO, sourceAuthorizationVersion = null} = grant;
		const newer = {$gt: [sourceVersion, projectionSourceVersion]};
		const equal = {$eq: [sourceVersion, projectionSourceVersion]};
		const sameMembership = {
			$eq: [
				{$ifNull: ['$sourceMembershipVersion', mongo.Long.ZERO]}, sourceMembershipVersion,
			],
		};
		// A legacy snapshot can fill a missing stamp, but equal-version conflicting grants cannot be rebound.
		const compatibleAuthorization = sourceAuthorizationVersion === null
			? true
			: {
				$or: [
					{$eq: [{$ifNull: ['$sourceAuthorizationVersion', null]}, null]},
					{$eq: ['$sourceAuthorizationVersion', sourceAuthorizationVersion]},
				],
			};
		const permission = (field: string, isAllowed: boolean) => ({
			$cond: [
				newer,
				isAllowed,
				{$cond: [equal, {$and: [sameMembership, compatibleAuthorization, {$ifNull: [`$${field}`, false]}, isAllowed]}, {$ifNull: [`$${field}`, false]}]},
			],
		});
		await this.model.updateOne({projectId, userId}, [{
			$set: {
				projectId,
				userId,
				read: permission('read', canRead),
				write: permission('write', canWrite),
				sourceOccurredAt: {$cond: [newer, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}]},
				sourceVersion: {$cond: [newer, sourceVersion, projectionSourceVersion]},
				sourceAuthorizationVersion: {
					$cond: [
						newer,
						sourceAuthorizationVersion,
						{$cond: [{$and: [equal, sameMembership]}, {$ifNull: ['$sourceAuthorizationVersion', sourceAuthorizationVersion]}, {$ifNull: ['$sourceAuthorizationVersion', null]}]},
					],
				},
				sourceMembershipVersion: {
					$cond: [
						newer,
						sourceMembershipVersion,
						{$ifNull: ['$sourceMembershipVersion', mongo.Long.ZERO]},
					],
				},
			},
		}], {upsert: true, timestamps: false, updatePipeline: true});
	}
}
