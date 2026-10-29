import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ChannelProjection} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	activeProjectionCondition,
	deletedProjectionCondition,
	isProjectionUpdateApplied,
	projectionTimestampFields,
	projectionSourceVersion,
	PROJECTION_EPOCH,
} from './versioned-projection.util.js';

export type ChannelProjectionView = {
	channelId: number;
	teamId: Nullable<number>;
	projectId: Nullable<number>;
	name: string;
	type: string;
	viewType: string;
	description: Nullable<string>;
	isPrivate: boolean;
	position: number;
};

export type ChannelProjectionState = {
	deleted: boolean;
} & ChannelProjectionView;

export type ChannelProjectionEvent = {
	eventType: 'CREATED' | 'UPDATED';
	occurredAt: Date;
	sourceVersion: mongo.Long;
} & ChannelProjectionView;

@injectable()
export class ChannelProjectionRepository {
	constructor(@injectModel(ChannelProjection.name) private readonly model: Model<ChannelProjection>) {}

	async findById(channelId: number): Promise<Nullable<ChannelProjectionView>> {
		return this.model.findOne({channelId, deleted: {$ne: true}}).lean<ChannelProjectionView>();
	}

	async findByIdIncludingDeleted(channelId: number): Promise<Nullable<ChannelProjectionState>> {
		return this.model.findOne({channelId}).lean<ChannelProjectionState>();
	}

	async findByIds(channelIds: number[]): Promise<ChannelProjectionView[]> {
		if (channelIds.length === 0) {
			return [];
		}

		return this.model.find(
			{channelId: {$in: channelIds}, deleted: {$ne: true}},
		).lean<ChannelProjectionView[]>();
	}

	async findIdsByTeamId(teamId: number): Promise<number[]> {
		return this.model.distinct('channelId', {teamId, deleted: {$ne: true}});
	}

	async findIdsByProjectId(projectId: number): Promise<number[]> {
		return this.model.distinct('channelId', {projectId, deleted: {$ne: true}});
	}

	async searchByTeamAndName(
		teamId: number,
		query: string,
	): Promise<ChannelProjectionView[]> {
		const escapedQuery = query.replaceAll(/[$\(\)*+.?\[\\\]^\{\|\}]/gv, String.raw`\$&`);
		return this.model
			.find({
				teamId,
				deleted: {$ne: true},
				name: {$regex: escapedQuery, $options: 'i'},
			})
			.sort({position: 1, channelId: 1})
			.lean<ChannelProjectionView[]>();
	}

	async upsert(event: ChannelProjectionEvent): Promise<boolean> {
		const {
			channelId,
			teamId,
			projectId,
			name,
			type,
			viewType,
			description,
			isPrivate,
			position,
			occurredAt,
			sourceVersion,
		} = event;
		const applyCondition = activeProjectionCondition(sourceVersion);
		const result = await this.model.updateOne(
			{channelId},
			[{
				$set: {
					channelId,
					teamId: {$cond: [applyCondition, {$literal: teamId}, {$ifNull: ['$teamId', null]}]},
					projectId: {
						$cond: [applyCondition, {$literal: projectId}, {$ifNull: ['$projectId', null]}],
					},
					name: {$cond: [applyCondition, {$literal: name}, {$ifNull: ['$name', '']}]},
					type: {$cond: [applyCondition, {$literal: type}, {$ifNull: ['$type', '']}]},
					viewType: {$cond: [applyCondition, {$literal: viewType}, {$ifNull: ['$viewType', '']}]},
					description: {
						$cond: [applyCondition, {$literal: description}, {$ifNull: ['$description', null]}],
					},
					isPrivate: {$cond: [applyCondition, isPrivate, {$ifNull: ['$isPrivate', false]}]},
					position: {$cond: [applyCondition, position, {$ifNull: ['$position', 0]}]},
					deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
					sourceOccurredAt: {
						$cond: [applyCondition, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
					},
					sourceVersion: {$cond: [applyCondition, sourceVersion, projectionSourceVersion]},
					...projectionTimestampFields(applyCondition, occurredAt),
				},
			}],
			{upsert: true, timestamps: false, updatePipeline: true},
		);
		return isProjectionUpdateApplied(result);
	}

	async remove(channelId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
		const applyCondition = deletedProjectionCondition(sourceVersion);
		const result = await this.model.updateOne(
			{channelId},
			[{
				$set: {
					channelId,
					teamId: {$ifNull: ['$teamId', null]},
					projectId: {$ifNull: ['$projectId', null]},
					name: {$ifNull: ['$name', '']},
					type: {$ifNull: ['$type', '']},
					viewType: {$ifNull: ['$viewType', '']},
					description: {$ifNull: ['$description', null]},
					isPrivate: {$ifNull: ['$isPrivate', false]},
					position: {$ifNull: ['$position', 0]},
					deleted: {$cond: [applyCondition, true, {$ifNull: ['$deleted', false]}]},
					sourceOccurredAt: {
						$cond: [applyCondition, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
					},
					sourceVersion: {$cond: [applyCondition, sourceVersion, projectionSourceVersion]},
					...projectionTimestampFields(applyCondition, occurredAt),
				},
			}],
			{upsert: true, timestamps: false, updatePipeline: true},
		);
		return isProjectionUpdateApplied(result);
	}
}
