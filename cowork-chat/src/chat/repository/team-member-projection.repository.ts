import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {TeamMemberProjection} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	deletedProjectionCondition,
	isProjectionUpdateApplied,
	projectionTimestampFields,
	projectionSourceVersion,
	PROJECTION_EPOCH,
} from './versioned-projection.util.js';

export type TeamMemberProjectionView = {
	teamId: number;
	userId: number;
	role: string;
	teamName: string;
	// 호환 정리(#596): 2026-11-01 05:00 KST 이후 구형 Kafka·Mongo 멤버 sourceVersion 보정과 rollback 종료 확인 후 조회 필드 필수화.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	sourceVersion?: bigint | mongo.Long;
	// 호환 정리(#596): 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 nullable fallback 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	authorizationVersion?: Nullable<bigint | mongo.Long>;
};

export type VersionedTeamMemberProjectionView = {
	nickname: Nullable<string>;
	occurredAt: Date;
	sourceVersion: mongo.Long;
} & TeamMemberProjectionView;

@injectable()
export class TeamMemberProjectionRepository {
	constructor(@injectModel(TeamMemberProjection.name) private readonly model: Model<TeamMemberProjection>) {}

	async findByTeamIds(teamIds: number[]): Promise<TeamMemberProjectionView[]> {
		return this.model.find({teamId: {$in: teamIds}, deleted: {$ne: true}}).lean<TeamMemberProjectionView[]>();
	}

	async exists(teamId: number, userId: number): Promise<boolean> {
		return (await this.model.exists({teamId, userId, deleted: {$ne: true}})) !== null;
	}

	async findByTeamIdsAndUserIds(
		teamIds: number[],
		userIds: number[],
	): Promise<TeamMemberProjectionView[]> {
		if (teamIds.length === 0 || userIds.length === 0) {
			return [];
		}

		return this.model.find(
			{teamId: {$in: teamIds}, userId: {$in: userIds}, deleted: {$ne: true}},
			{
				_id: 0, teamId: 1, userId: 1, role: 1, teamName: 1, sourceVersion: 1, authorizationVersion: 1,
			},
		).lean<TeamMemberProjectionView[]>();
	}

	async findNicknames(teamId: number, userIds: number[]): Promise<Map<number, string>> {
		if (userIds.length === 0) {
			return new Map();
		}

		const members = await this.model.find(
			{
				teamId, userId: {$in: userIds}, deleted: {$ne: true}, nickname: {$nin: [null, '']},
			},
			{_id: 0, userId: 1, nickname: 1},
		).lean<Array<{userId: number; nickname: string}>>();
		return new Map(members.map(member => [member.userId, member.nickname]));
	}

	async upsert(member: VersionedTeamMemberProjectionView): Promise<boolean> {
		const newer = {$gt: [member.sourceVersion, projectionSourceVersion]};
		const compatibleEqual = {
			$and: [
				{$eq: [member.sourceVersion, projectionSourceVersion]},
				{$ne: ['$deleted', true]},
				{$eq: ['$role', {$literal: member.role}]},
			],
		};
		const applyCondition = {$or: [newer, compatibleEqual]};
		const authorizationVersion = member.authorizationVersion ?? null;
		const result = await this.model.updateOne(
			{teamId: member.teamId, userId: member.userId},
			[{
				$set: {
					teamId: member.teamId,
					userId: member.userId,
					role: {$cond: [applyCondition, {$literal: member.role}, {$ifNull: ['$role', '']}]},
					teamName: {
						$cond: [applyCondition, {$literal: member.teamName}, {$ifNull: ['$teamName', '']}],
					},
					nickname: {
						$cond: [applyCondition, {$literal: member.nickname}, {$ifNull: ['$nickname', null]}],
					},
					deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
					sourceOccurredAt: {
						$cond: [
							applyCondition,
							member.occurredAt,
							{$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]},
						],
					},
					sourceVersion: {
						$cond: [applyCondition, member.sourceVersion, projectionSourceVersion],
					},
					authorizationVersion: {
						$cond: [
							newer,
							authorizationVersion,
							{$cond: [compatibleEqual, {$max: [{$ifNull: ['$authorizationVersion', null]}, authorizationVersion]}, {$ifNull: ['$authorizationVersion', null]}]},
						],
					},
					...projectionTimestampFields(applyCondition, member.occurredAt),
				},
			}],
			{upsert: true, timestamps: false, updatePipeline: true},
		);
		return isProjectionUpdateApplied(result);
	}

	async remove(
		member: {
			teamId: number;
			userId: number;
			occurredAt: Date;
			sourceVersion: mongo.Long;
			authorizationVersion?: Nullable<mongo.Long>;
			role?: string;
		},

	): Promise<boolean> {
		const {teamId, userId, occurredAt, sourceVersion, authorizationVersion = null, role} = member;
		const applyCondition = deletedProjectionCondition(sourceVersion);
		const equal = {$eq: [sourceVersion, projectionSourceVersion]};
		const alreadyDeleted = {$and: [equal, {$eq: ['$deleted', true]}]};
		const incomingRole = typeof role === 'string' && role.length > 0 ? role : null;
		// 호환 정리(#596): 2026-11-01 05:00 KST 이후 구형 삭제 snapshot·Mongo 역할과 auth 보정 및 rollback 종료 확인 후 빈 역할 호환 제거.
		// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
		const compatibleRole = incomingRole === null
			? false
			: {$or: [{$eq: [{$ifNull: ['$role', '']}, '']}, {$eq: ['$role', {$literal: incomingRole}]}]};
		const updateRole = incomingRole === null ? false : {$and: [applyCondition, {$or: [{$not: [alreadyDeleted]}, compatibleRole]}]};
		const result = await this.model.updateOne(
			{teamId, userId},
			[{
				$set: {
					teamId,
					userId,
					role: {$cond: [updateRole, {$literal: incomingRole}, {$ifNull: ['$role', '']}]},
					teamName: {$ifNull: ['$teamName', '']},
					nickname: {$ifNull: ['$nickname', null]},
					deleted: {$cond: [applyCondition, true, {$ifNull: ['$deleted', false]}]},
					sourceOccurredAt: {
						$cond: [applyCondition, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
					},
					sourceVersion: {$cond: [applyCondition, sourceVersion, projectionSourceVersion]},
					authorizationVersion: {
						$cond: [
							applyCondition,
							{
								$cond: [
									alreadyDeleted,
									{$cond: [compatibleRole, {$max: [{$ifNull: ['$authorizationVersion', null]}, authorizationVersion]}, {$ifNull: ['$authorizationVersion', null]}]},
									authorizationVersion,
								],
							},
							{$ifNull: ['$authorizationVersion', null]},
						],
					},
					...projectionTimestampFields(applyCondition, occurredAt),
				},
			}],
			{upsert: true, timestamps: false, updatePipeline: true},
		);
		return isProjectionUpdateApplied(result);
	}
}
