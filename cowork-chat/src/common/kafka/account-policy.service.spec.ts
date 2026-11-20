import {ServiceUnavailableException} from '@nestjs/common';
import {type Model} from 'mongoose';
import {AccountPolicyService} from './account-policy.service.js';
import {type AccountPolicy} from './account-policy.schema.js';
import {type ProjectionReadinessService} from './projection-readiness.service.js';
import {ProjectionContractError} from './projection-message.processor.js';

function fixture(isReady: boolean) {
	const model = {distinct: jest.fn(), exists: jest.fn(), updateOne: jest.fn()};
	const readiness = {isCaughtUp: jest.fn().mockReturnValue(isReady)};
	const service = new AccountPolicyService(model as unknown as Model<AccountPolicy>, readiness as unknown as ProjectionReadinessService);
	return {model, readiness, service};
}

describe('AccountPolicyService', () => {
	it('동기화가 지연되면 저장된 권한을 사용하지 않는다', async () => {
		const {service, model, readiness} = fixture(false);
		await expect(service.allows(7, 'view_chat_projections')).rejects.toThrow(ServiceUnavailableException);
		expect(readiness.isCaughtUp).toHaveBeenCalledWith('accountPolicy');
		expect(model.distinct).not.toHaveBeenCalled();
	});

	it('요청자의 현재 계정 역할에서 해당 작업의 권한을 조회한다', async () => {
		const {service, model} = fixture(true);
		model.distinct.mockResolvedValueOnce([3]).mockResolvedValueOnce([]);
		model.exists.mockResolvedValueOnce({_id: 3}).mockResolvedValueOnce(null);
		expect(await service.allows(7, 'manage_chat_search_index')).toBe(true);
		expect(await service.allows(7, 'manage_chat_search_index')).toBe(false);
		expect(model.distinct).toHaveBeenCalledWith('roleId', {kind: 'ASSIGNMENT', accountId: 7, deleted: false});
		expect(model.exists).toHaveBeenNthCalledWith(1, {
			kind: 'ROLE', roleId: {$in: [3]}, deleted: false, isDefault: false, 'permissions.manage_chat_search_index': true,
		});
	});

	it('할당 키와 사용자가 다르면 저장 전에 거부한다', async () => {
		const {service, model} = fixture(true);
		await expect(service.apply({
			eventType: 'ASSIGNMENT', accountId: 7, roleId: 3, deleted: false, occurredAt: '2026-11-19T00:00:00Z',
		}, 'assignment:8:3')).rejects.toThrow(ProjectionContractError);
		expect(model.updateOne).not.toHaveBeenCalled();
	});
});
