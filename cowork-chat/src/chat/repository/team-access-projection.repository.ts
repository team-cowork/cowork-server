import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {TeamAccessProjection} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	projectionSourceVersion,
	projectionTimestampFields,
	PROJECTION_EPOCH,
} from './versioned-projection.util.js';

export type TeamAccessDecision = {
	userId: number;
	teamId: number;
	administrator: boolean;
	occurredAt: Date;
	sourceVersion: mongo.Long;
	sourceMembershipVersion: mongo.Long;
	sourceAuthorizationVersion: Nullable<mongo.Long>;
};

@injectable()
export class TeamAccessProjectionRepository {
	constructor(@injectModel(TeamAccessProjection.name) private readonly model: Model<TeamAccessProjection>) {}

	async hasAdministratorPolicy(teamId: number, userId: number, authorizationVersion: Nullable<bigint | mongo.Long | undefined>): Promise<boolean> {
		if (authorizationVersion === null || authorizationVersion === undefined) {
			return false;
		}

		const sourceAuthorizationVersion = typeof authorizationVersion === 'bigint' ? mongo.Long.fromBigInt(authorizationVersion) : authorizationVersion;
		return sourceAuthorizationVersion.greaterThan(mongo.Long.ZERO)
			&& (await this.model.exists({
				teamId, userId, administrator: true, $expr: {$eq: ['$sourceAuthorizationVersion', sourceAuthorizationVersion]},
			})) !== null;
	}

	async upsert(decision: TeamAccessDecision): Promise<void> {
		const {
			teamId, userId, administrator, occurredAt, sourceVersion, sourceMembershipVersion, sourceAuthorizationVersion,
		} = decision;
		const newer = {$gt: [sourceVersion, projectionSourceVersion]};
		const equal = {$eq: [sourceVersion, projectionSourceVersion]};
		const sameSource = {
			$and: [
				{$eq: ['$sourceMembershipVersion', sourceMembershipVersion]},
				{$eq: ['$sourceAuthorizationVersion', sourceAuthorizationVersion]},
			],
		};
		const equalVersionGrant = {$and: ['$administrator', sameSource, administrator]};
		await this.model.updateOne({teamId, userId}, [{
			$set: {
				teamId,
				userId,
				administrator: {$cond: [newer, administrator, {$cond: [equal, equalVersionGrant, '$administrator']}]},
				sourceOccurredAt: {$cond: [newer, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}]},
				sourceVersion: {$cond: [newer, sourceVersion, projectionSourceVersion]},
				sourceMembershipVersion: {$cond: [newer, sourceMembershipVersion, '$sourceMembershipVersion']},
				sourceAuthorizationVersion: {$cond: [newer, sourceAuthorizationVersion, '$sourceAuthorizationVersion']},
				...projectionTimestampFields(newer, occurredAt),
			},
		}], {upsert: true, timestamps: false, updatePipeline: true});
	}
}
