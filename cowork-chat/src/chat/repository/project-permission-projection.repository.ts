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
				$expr: {$eq: ['$sourceAuthorizationVersion', sourceAuthorizationVersion]},
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
			sourceMembershipVersion: mongo.Long;
			sourceAuthorizationVersion: Nullable<mongo.Long>;
		},

	): Promise<void> {
		const {projectId, userId, canRead, canWrite, occurredAt, sourceVersion, sourceMembershipVersion, sourceAuthorizationVersion} = grant;
		const newer = {$gt: [sourceVersion, projectionSourceVersion]};
		const equal = {$eq: [sourceVersion, projectionSourceVersion]};
		const sameMembership = {
			$eq: [
				'$sourceMembershipVersion', sourceMembershipVersion,
			],
		};
		const compatibleAuthorization = {$eq: ['$sourceAuthorizationVersion', sourceAuthorizationVersion]};
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
						'$sourceAuthorizationVersion',
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
