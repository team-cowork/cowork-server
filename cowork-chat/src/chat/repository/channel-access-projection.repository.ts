import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ChannelAccessProjection} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	isProjectionUpdateApplied,
	projectionSourceVersion,
	projectionTimestampFields,
	PROJECTION_EPOCH,
} from './versioned-projection.util.js';

export type ChannelAccessProjectionView = {
	channelId: number;
	userId: number;
	teamId: number;
	allowedPermissions: string[];
	readPrivate: boolean;
	sourceAuthorizationVersion: Nullable<bigint | mongo.Long>;
};

export type ChannelAccessDecision = {
	channelId: number;
	userId: number;
	teamId: number;
	allowedPermissions: string[];
	readPrivate: boolean;
	occurredAt: Date;
	sourceVersion: mongo.Long;
	sourceMembershipVersion: mongo.Long;
	sourceAuthorizationVersion: Nullable<mongo.Long>;
};

@injectable()
export class ChannelAccessProjectionRepository {
	constructor(@injectModel(ChannelAccessProjection.name) private readonly model: Model<ChannelAccessProjection>) {}

	async findByChannelIdsAndUserIds(channelIds: number[], userIds: number[]): Promise<ChannelAccessProjectionView[]> {
		if (channelIds.length === 0 || userIds.length === 0) {
			return [];
		}

		return this.model.find(
			{channelId: {$in: channelIds}, userId: {$in: userIds}},
			{
				_id: 0, channelId: 1, userId: 1, teamId: 1, allowedPermissions: 1, readPrivate: 1, sourceAuthorizationVersion: 1,
			},
		).lean<ChannelAccessProjectionView[]>();
	}

	/**
     새 버전은 덮어쓴다. 같은 버전은 replay된 snapshot이므로 두 기록이 모두 허용한 권한만 남기고,
     팀이나 원본 version이 다르면 모두 거부한다. 서로 다른 같은 버전 기록이 권한을 되살리지 못하게 하기 위해서다.
     값이 바뀌면 true를 반환한다.
     */
	async upsert(decision: ChannelAccessDecision): Promise<boolean> {
		const {
			channelId, userId, teamId, allowedPermissions, readPrivate, occurredAt, sourceVersion, sourceMembershipVersion, sourceAuthorizationVersion,
		} = decision;
		const newer = {$gt: [sourceVersion, projectionSourceVersion]};
		const equal = {$eq: [sourceVersion, projectionSourceVersion]};
		const sameSource = {
			$and: [
				{$eq: ['$teamId', teamId]},
				{$eq: ['$sourceMembershipVersion', sourceMembershipVersion]},
				{$eq: ['$sourceAuthorizationVersion', sourceAuthorizationVersion]},
			],
		};
		const select = (field: string, incoming: unknown, sameVersion: unknown) => ({
			$cond: [newer, incoming, {$cond: [equal, sameVersion, `$${field}`]}],
		});
		const result = await this.model.updateOne({channelId, userId}, [{
			$set: {
				channelId,
				userId,
				allowedPermissions: select(
					'allowedPermissions',
					{$literal: allowedPermissions},
					{$cond: [sameSource, {$setIntersection: ['$allowedPermissions', {$literal: allowedPermissions}]}, []]},
				),
				readPrivate: select('readPrivate', readPrivate, {$and: [sameSource, '$readPrivate', readPrivate]}),
				teamId: select('teamId', teamId, '$teamId'),
				sourceOccurredAt: {$cond: [newer, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}]},
				sourceVersion: {$cond: [newer, sourceVersion, projectionSourceVersion]},
				sourceMembershipVersion: select('sourceMembershipVersion', sourceMembershipVersion, '$sourceMembershipVersion'),
				sourceAuthorizationVersion: select('sourceAuthorizationVersion', sourceAuthorizationVersion, '$sourceAuthorizationVersion'),
				...projectionTimestampFields(newer, occurredAt),
			},
		}], {upsert: true, timestamps: false, updatePipeline: true});
		return isProjectionUpdateApplied(result);
	}
}
