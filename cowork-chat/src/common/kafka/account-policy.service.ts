import {Injectable as injectable, ServiceUnavailableException} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model} from 'mongoose';
import {isBooleanRecord} from '../util/boolean-record.util.js';
import {parseEventTime} from '../util/event-time.util.js';
import {isSafePositiveInteger} from '../util/safe-integer.util.js';
import {ProjectionContractError} from './projection-message.processor.js';
import {AccountPolicy} from './account-policy.schema.js';
import {ProjectionReadinessService} from './projection-readiness.service.js';

export type ChatAccountPermission = 'manage_chat_search_index' | 'manage_chat_message_quarantine' | 'view_chat_projections' | 'rebuild_chat_projections';

@injectable()
export class AccountPolicyService {
	constructor(
		@injectModel(AccountPolicy.name) private readonly model: Model<AccountPolicy>,
		private readonly readiness: ProjectionReadinessService,
	) {}

	async allows(userId: number, permission: ChatAccountPermission): Promise<boolean> {
		if (!this.readiness.isCaughtUp('accountPolicy')) {
			throw new ServiceUnavailableException('계정 권한을 동기화하는 중입니다.');
		}

		const roles = await this.model.distinct('roleId', {kind: 'ASSIGNMENT', accountId: userId, deleted: false});
		return Boolean(await this.model.exists({
			kind: 'ROLE', roleId: {$in: roles}, deleted: false, isDefault: false, [`permissions.${permission}`]: true,
		}));
	}

	async apply(payload: unknown, key?: string): Promise<void> {
		if (payload === null || typeof payload !== 'object') {
			throw new ProjectionContractError('invalid account policy payload');
		}

		const event = payload as Record<string, unknown>;
		const time = parseEventTime(event.occurredAt);
		if (!time || !isSafePositiveInteger(event.roleId) || typeof event.deleted !== 'boolean') {
			throw new ProjectionContractError('invalid account policy version or identity');
		}

		const {roleId, accountId, isDefault, permissions, deleted, eventType: kind} = event;
		let fields;
		let expectedKey: string;
		if (kind === 'ROLE' && typeof isDefault === 'boolean' && isBooleanRecord(permissions)) {
			expectedKey = `role:${roleId}`;
			fields = {isDefault, permissions};
		} else if (kind === 'ASSIGNMENT' && isSafePositiveInteger(accountId)) {
			expectedKey = `assignment:${accountId}:${roleId}`;
			fields = {accountId};
		} else {
			throw new ProjectionContractError('invalid account policy state');
		}

		if (expectedKey !== key) {
			throw new ProjectionContractError('account policy key does not match identity');
		}

		const {sourceVersion} = time;
		const apply = deleted ? {$lte: ['$sourceVersion', sourceVersion]} : {$lt: ['$sourceVersion', sourceVersion]};
		const state = {
			key, kind, roleId, deleted, sourceVersion, ...fields,
		};
		const updated = {$mergeObjects: ['$$ROOT', {$literal: state}]};
		await this.model.updateOne(
			{key},
			[{$replaceWith: {$cond: [apply, updated, '$$ROOT']}}],
			{upsert: true, updatePipeline: true},
		);
	}
}
