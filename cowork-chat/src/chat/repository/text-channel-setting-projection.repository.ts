import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {TextChannelSettingProjection} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	activeProjectionCondition,
	projectionSourceVersion,
	projectionTimestampFields,
	isProjectionUpdateApplied,
	PROJECTION_EPOCH,
} from './versioned-projection.util.js';

@injectable()
export class TextChannelSettingProjectionRepository {
	constructor(
		@injectModel(TextChannelSettingProjection.name)
		private readonly model: Model<TextChannelSettingProjection>,
	) {}

	async upsert(input: {
		channelId: number;
		slowmodeSeconds: number;
		occurredAt: Date;
		sourceVersion: mongo.Long;
	}): Promise<boolean> {
		const applyCondition = activeProjectionCondition(input.sourceVersion);
		const result = await this.model.updateOne(
			{channelId: input.channelId},
			[{
				$set: {
					channelId: input.channelId,
					slowmodeSeconds: {
						$cond: [applyCondition, input.slowmodeSeconds, {$ifNull: ['$slowmodeSeconds', 0]}],
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

	async findSlowmodeSeconds(channelId: number): Promise<number> {
		const setting = await this.model.findOne(
			{channelId, deleted: {$ne: true}},
			{_id: 0, slowmodeSeconds: 1},
		).lean<Nullable<{slowmodeSeconds: number}>>();
		return setting?.slowmodeSeconds ?? 0;
	}
}
