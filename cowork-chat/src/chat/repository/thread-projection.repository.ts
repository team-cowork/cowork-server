import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, type mongo} from 'mongoose';
import {ThreadProjection, type ThreadStatus} from '../schema/thread-projection.schema.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	activeProjectionCondition,
	deletedProjectionCondition,
	isProjectionUpdateApplied,
	projectionTimestampFields,
} from './versioned-projection.util.js';

export type ThreadState = {
	threadId: number;
	channelId: number;
	name: string;
	parentMessageId: string;
	createdBy: number;
	status: ThreadStatus;
	archived: boolean;
	deleted: boolean;
	occurredAt: Date;
	sourceVersion: mongo.Long;
};

export type ThreadProjectionRow = Pick<ThreadProjection, 'threadId' | 'channelId' | 'name' | 'parentMessageId' | 'createdBy' | 'status' | 'archived' | 'deleted'>;

export type ThreadApplyResult = {
	/**
    이 레코드로 projection이 실제로 바뀌었는지 여부. 같은 내용의 재전달·snapshot은 `false`
    */
	applied: boolean;
	/**
    적용 전 상태. 처음 받은 스레드이면 `null`
    */
	previous: Nullable<ThreadProjectionRow>;
};

@injectable()
export class ThreadProjectionRepository {
	constructor(@injectModel(ThreadProjection.name) private readonly model: Model<ThreadProjection>) {}

	async findActive(threadId: number): Promise<Nullable<ThreadProjectionRow>> {
		return this.model.findOne({threadId, deleted: false}).lean();
	}

	/**
     버전이 더 최신인 상태만 적용한다. 같은 버전이면 삭제가 우선한다.
     스레드 상태 토픽은 파티션 하나를 consumer 하나가 순서대로 처리하므로, 적용 전 조회와 갱신 사이에 다른 쓰기가 끼지 않는다.
     */
	async apply(state: ThreadState): Promise<ThreadApplyResult> {
		const previous = await this.model.findOne({threadId: state.threadId}).lean();
		const condition = state.deleted ? deletedProjectionCondition(state.sourceVersion) : activeProjectionCondition(state.sourceVersion);
		const values = {
			channelId: state.channelId,
			name: state.name,
			parentMessageId: state.parentMessageId,
			createdBy: state.createdBy,
			status: state.status,
			archived: state.archived,
			deleted: state.deleted,
			sourceVersion: state.sourceVersion,
			sourceOccurredAt: state.occurredAt,
		};
		const result = await this.model.updateOne({threadId: state.threadId}, [{
			$set: {
				threadId: state.threadId,
				...Object.fromEntries(Object.entries(values).map(([key, value]) => [key, {$cond: [condition, {$literal: value}, `$${key}`]}])),
				...projectionTimestampFields(condition, state.occurredAt),
			},
		}], {upsert: true, timestamps: false, updatePipeline: true});
		return {applied: isProjectionUpdateApplied(result), previous};
	}
}
