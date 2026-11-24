import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, Types, type QueryFilter} from 'mongoose';
import {type Nullable} from '../../common/type/nullable.js';
import {ScheduledMessage, type ScheduledMessageFailureReason, type ScheduledMessageStatus} from './scheduled-message.schema.js';

export type ScheduledMessageRecord = ScheduledMessage & {_id: Types.ObjectId};
export type ClaimedScheduledMessage = ScheduledMessageRecord & {claimId: string};

const WAITING_STATUSES: ScheduledMessageStatus[] = ['PENDING', 'DISPATCHING'];
/**
`SENT`는 consumer가 메시지를 저장하기 전에 업로드 회수가 첨부를 지우지 않도록 기록이 지워질 때까지 참조로 센다.
*/
const REFERENCING_STATUSES: ScheduledMessageStatus[] = ['PENDING', 'DISPATCHING', 'SENT'];
const WAITING_LIST_LIMIT = 200;
const FAILED_LIST_LIMIT = 100;

type ClaimOptions = {now: Date; leaseMs: number; batchSize: number};

@injectable()
export class ScheduledMessageRepository {
	constructor(@injectModel(ScheduledMessage.name) private readonly model: Model<ScheduledMessage>) {}

	private async claim(
		filter: QueryFilter<ScheduledMessage>,
		set: Partial<Pick<ScheduledMessage, 'status'>>,
		{now, leaseMs, batchSize}: ClaimOptions,
	): Promise<ClaimedScheduledMessage[]> {
		const due = {...filter, nextAttemptAt: {$lte: now}};
		const candidates = await this.model.find(due).sort({nextAttemptAt: 1}).limit(batchSize).select('_id').lean();
		if (candidates.length === 0) {
			return [];
		}

		const ids = candidates.map(candidate => candidate._id);
		const claimId = new Types.ObjectId().toString();
		await this.model.updateMany(
			{...due, _id: {$in: ids}},
			{$set: {...set, nextAttemptAt: new Date(now.getTime() + leaseMs), claimId}},
		);
		return this.model.find({_id: {$in: ids}, claimId}).lean<ClaimedScheduledMessage[]>();
	}

	async create(message: Omit<ScheduledMessage, 'createdAt' | 'updatedAt'>): Promise<ScheduledMessageRecord> {
		const created = await this.model.create(message);
		return created.toObject<ScheduledMessageRecord>();
	}

	async findByClientMessageId(clientMessageId: string): Promise<Nullable<ScheduledMessageRecord>> {
		return this.model.findOne({clientMessageId}).lean<ScheduledMessageRecord>();
	}

	async countWaiting(authorId: number): Promise<number> {
		return this.model.countDocuments({authorId, status: {$in: WAITING_STATUSES}});
	}

	/**
    대기 중인 예약을 발송 시각 순서로 먼저 돌려주고, 그 뒤에 실패한 예약을 최근 실패 순서로 돌려준다.
    실패한 예약이 많아도 대기 중인 예약이 목록에서 밀려나지 않게 따로 조회한다.
    */
	async listForAuthor(authorId: number): Promise<ScheduledMessageRecord[]> {
		const [waiting, failed] = await Promise.all([
			this.model.find({authorId, status: {$in: WAITING_STATUSES}}).sort({scheduledAt: 1}).limit(WAITING_LIST_LIMIT).lean<ScheduledMessageRecord[]>(),
			this.model.find({authorId, status: 'FAILED'}).sort({failedAt: -1}).limit(FAILED_LIST_LIMIT).lean<ScheduledMessageRecord[]>(),
		]);
		return [...waiting, ...failed];
	}

	async findForAuthor(id: string, authorId: number): Promise<Nullable<ScheduledMessageRecord>> {
		return this.model.findOne({_id: id, authorId}).lean<ScheduledMessageRecord>();
	}

	/**
    워커가 점유하지 않은 대기 예약만 바꾼다.

    @returns 바꾼 예약. 대기 상태가 아니면 null
    */
	async updatePending(id: string, authorId: number, update: {content?: string; scheduledAt?: Date}): Promise<Nullable<ScheduledMessageRecord>> {
		const set = update.scheduledAt === undefined ? update : {...update, nextAttemptAt: update.scheduledAt};
		return this.model.findOneAndUpdate(
			{_id: id, authorId, status: 'PENDING'},
			{$set: set},
			{returnDocument: 'after'},
		).lean<ScheduledMessageRecord>();
	}

	/**
    워커가 점유하지 않은 대기 예약이나 실패 예약을 취소 상태로 바꾼다. 첨부 회수는 워커가 후처리로 한다.
    이미 후처리한 실패 예약도 다시 후처리해 기록을 지운다. 첨부 회수는 반복해도 결과가 같다.

    @returns 취소한 예약. 취소할 수 있는 상태가 아니면 null
    */
	async cancel(id: string, authorId: number, now: Date): Promise<Nullable<ScheduledMessageRecord>> {
		return this.model.findOneAndUpdate(
			{_id: id, authorId, status: {$in: ['PENDING', 'FAILED']}},
			{
				$set: {
					status: 'CANCELED', finalizedAt: null, nextAttemptAt: now, claimId: null,
				},
			},
			{returnDocument: 'after'},
		).lean<ScheduledMessageRecord>();
	}

	/**
    발송 시각이 된 대기 예약과 점유가 끝난 발송 중 예약을 점유한다.
    */
	async claimDue(now: Date, leaseMs: number, batchSize: number): Promise<ClaimedScheduledMessage[]> {
		return this.claim({status: {$in: WAITING_STATUSES}}, {status: 'DISPATCHING'}, {now, leaseMs, batchSize});
	}

	/**
    후처리하지 않은 실패 예약과 취소한 예약을 점유한다.
    */
	async claimUnfinalized(now: Date, leaseMs: number, batchSize: number): Promise<ClaimedScheduledMessage[]> {
		return this.claim({status: {$in: ['FAILED', 'CANCELED']}, finalizedAt: null}, {}, {now, leaseMs, batchSize});
	}

	async markSent(id: Types.ObjectId, claimId: string, expiresAt: Date): Promise<void> {
		await this.model.updateOne({_id: id, claimId, status: 'DISPATCHING'}, {$set: {status: 'SENT', expiresAt, lastError: null}});
	}

	/**
    발행 전 검사에서 일시 오류가 난 예약을 대기 상태로 되돌린다. 작성자는 재시도 사이에 수정하거나 취소할 수 있다.
    */
	async markRetry(id: Types.ObjectId, claimId: string, nextAttemptAt: Date, error: string): Promise<void> {
		await this.model.updateOne(
			{_id: id, claimId, status: 'DISPATCHING'},
			{$set: {status: 'PENDING', nextAttemptAt, lastError: error}, $inc: {attempts: 1}},
		);
	}

	/**
    발행에서 오류가 난 예약을 발송 중 상태로 두고 재시도 시각만 미룬다. 브로커에는 기록되었을 수 있으므로
    작성자가 수정하거나 취소하지 못하게 한다. 다시 발행해도 같은 `clientMessageId`라 한 번만 저장된다.
    */
	async deferPublish(id: Types.ObjectId, claimId: string, nextAttemptAt: Date, error: string): Promise<void> {
		await this.model.updateOne(
			{_id: id, claimId, status: 'DISPATCHING'},
			{$set: {nextAttemptAt, lastError: error}, $inc: {attempts: 1}},
		);
	}

	/**
    실패하거나 취소한 예약이 실제로는 저장되었으면 발송한 예약으로 바꾼다. 점유 뒤 상태가 바뀌었으면 바꾸지 않는다.
    */
	async markDelivered({_id: id, claimId, status}: Pick<ClaimedScheduledMessage, '_id' | 'claimId' | 'status'>, expiresAt: Date): Promise<void> {
		await this.model.updateOne(
			{_id: id, claimId, status},
			{
				$set: {
					status: 'SENT', failureReason: null, failedAt: null, lastError: null, expiresAt,
				},
			},
		);
	}

	/**
    발송 중인 예약을 실패로 바꾼다. `nextAttemptAt`이 지금이므로 바로 다음 후처리 점유에서 첨부를 회수하고 알린다.

    @returns 실패로 바꿨으면 true. 다른 워커가 먼저 처리했으면 false
    */
	async markFailed({_id: id, claimId}: Pick<ClaimedScheduledMessage, '_id' | 'claimId'>, reason: ScheduledMessageFailureReason, now: Date): Promise<boolean> {
		const result = await this.model.updateOne(
			{_id: id, claimId, status: 'DISPATCHING'},
			{
				$set: {
					status: 'FAILED', failureReason: reason, failedAt: now, nextAttemptAt: now,
				},
			},
		);
		return result.modifiedCount > 0;
	}

	/**
    후처리를 끝냈다고 기록한다. 점유 뒤 작성자가 상태를 바꿨으면 기록하지 않아 바뀐 상태로 다시 후처리한다.
    */
	async markFinalized({_id: id, claimId, status}: Pick<ClaimedScheduledMessage, '_id' | 'claimId' | 'status'>, now: Date, expiresAt: Date): Promise<void> {
		await this.model.updateOne({_id: id, claimId, status}, {$set: {finalizedAt: now, expiresAt}});
	}

	async referencesObject(objectKey: string): Promise<boolean> {
		return (await this.model.exists({'attachments.objectKey': objectKey, status: {$in: REFERENCING_STATUSES}})) !== null;
	}

	/**
    아직 발송하지 않은 예약이 이 객체를 첨부로 갖는지 확인한다. 발송한 예약은 저장된 메시지가 첨부를 소유하므로 세지 않는다.
    */
	async waitsForObject(objectKey: string): Promise<boolean> {
		return (await this.model.exists({'attachments.objectKey': objectKey, status: {$in: WAITING_STATUSES}})) !== null;
	}
}
