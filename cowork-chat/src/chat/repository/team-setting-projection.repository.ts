import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {TeamSettingProjection} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	activeProjectionCondition,
	projectionSourceVersion,
	projectionTimestampFields,
	isProjectionUpdateApplied,
	PROJECTION_EPOCH,
} from './versioned-projection.util.js';

@injectable()
export class TeamSettingProjectionRepository {
	constructor(
		@injectModel(TeamSettingProjection.name)
		private readonly model: Model<TeamSettingProjection>,
	) {}

	async upsert(input: {
		teamId: number;
		tagSpamBlock: boolean;
		occurredAt: Date;
		sourceVersion: mongo.Long;
	}): Promise<boolean> {
		const applyCondition = activeProjectionCondition(input.sourceVersion);
		const result = await this.model.updateOne(
			{teamId: input.teamId},
			[{
				$set: {
					teamId: input.teamId,
					tagSpamBlock: {
						$cond: [applyCondition, input.tagSpamBlock, {$ifNull: ['$tagSpamBlock', false]}],
					},
					deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
					sourceOccurredAt: {
						$cond: [applyCondition, input.occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
					},
					sourceVersion: {$cond: [applyCondition, input.sourceVersion, projectionSourceVersion]},
					...projectionTimestampFields(applyCondition, input.occurredAt),
				},
			}],
			{upsert: true, timestamps: false, updatePipeline: true},
		);
		return isProjectionUpdateApplied(result);
	}

	async isTagSpamBlockEnabled(teamId: number): Promise<boolean> {
		const setting = await this.model.findOne(
			{teamId, deleted: {$ne: true}},
			{_id: 0, tagSpamBlock: 1},
		).lean<Nullable<{tagSpamBlock: boolean}>>();
		return setting?.tagSpamBlock ?? false;
	}
}
