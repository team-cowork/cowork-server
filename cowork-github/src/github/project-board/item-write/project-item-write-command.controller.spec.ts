import type {KafkaContext} from '@nestjs/microservices';
import {GithubClientError} from '../../github.errors.js';
import type {ProjectBoardApiService} from '../project-board-api.service.js';
import type {ProjectItemEditorService} from './project-item-editor.service.js';
import {ProjectItemWriteCommandController} from './project-item-write-command.controller.js';
import type {ProjectItemWriteResultProducer} from './project-item-write-result.producer.js';

const envelope = (commandType: string, payload: Record<string, unknown>) => ({
	schemaVersion: 1,
	operationId: '0f8fad5b-d9cb-469f-a165-70867728950e',
	idempotencyKey: 'github-project-item-write:key',
	commandType,
	owner: 'my-org',
	projectNodeId: 'PVT_1',
	itemId: 'PVTI_1',
	requestedBy: 10,
	occurredAt: '2026-11-17T00:00:00Z',
	payload,
});

describe('ProjectItemWriteCommandController', () => {
	let api: Record<'updateFieldValue' | 'clearFieldValue' | 'updatePosition', jest.Mock>;
	let resultProducer: {send: jest.Mock};
	let commitOffsets: jest.Mock;
	let context: KafkaContext;
	let controller: ProjectItemWriteCommandController;

	beforeEach(() => {
		api = {updateFieldValue: jest.fn(), clearFieldValue: jest.fn(), updatePosition: jest.fn()};
		resultProducer = {send: jest.fn()};
		commitOffsets = jest.fn();
		context = {
			getMessage: () => ({offset: '4'}),
			getConsumer: () => ({commitOffsets}),
			getTopic: () => 'github.project-item-write.command.v1',
			getPartition: () => 0,
		} as unknown as KafkaContext;
		controller = new ProjectItemWriteCommandController(
			api as unknown as ProjectBoardApiService,
			resultProducer as unknown as ProjectItemWriteResultProducer,
			{} as ProjectItemEditorService,
		);
	});

	it('컬럼 옵션으로 값을 바꾸고 요청한 위치로 옮긴 뒤 성공을 발행한다', async () => {
		await controller.handleCommand(envelope('MOVE_ITEM', {
			fieldId: 'F_STATUS', singleSelectOptionId: 'opt-done', reposition: true, afterItemId: 'PVTI_9',
		}), context);

		expect(api.updateFieldValue).toHaveBeenCalledWith({
			owner: 'my-org', projectNodeId: 'PVT_1', itemId: 'PVTI_1', fieldId: 'F_STATUS', value: {singleSelectOptionId: 'opt-done'},
		});
		expect(api.updatePosition).toHaveBeenCalledWith({
			owner: 'my-org', projectNodeId: 'PVT_1', itemId: 'PVTI_1', afterId: 'PVTI_9',
		});
		expect(resultProducer.send).toHaveBeenCalledWith(expect.objectContaining({status: 'SUCCEEDED', commandType: 'MOVE_ITEM'}));
		expect(commitOffsets).toHaveBeenCalledWith([{topic: 'github.project-item-write.command.v1', partition: 0, offset: '5'}]);
	});

	it('값이 없는 컬럼으로 옮기면 field 값을 지우고 위치는 그대로 둔다', async () => {
		await controller.handleCommand(envelope('MOVE_ITEM', {fieldId: 'F_STATUS', singleSelectOptionId: null, reposition: false}), context);

		expect(api.clearFieldValue).toHaveBeenCalledWith({
			owner: 'my-org', projectNodeId: 'PVT_1', itemId: 'PVTI_1', fieldId: 'F_STATUS',
		});
		expect(api.updatePosition).not.toHaveBeenCalled();
	});

	it('iteration 컬럼은 iterationId로 값을 바꾼다', async () => {
		await controller.handleCommand(envelope('MOVE_ITEM', {fieldId: 'F_SPRINT', iterationId: 'it-2', reposition: false}), context);

		expect(api.updateFieldValue).toHaveBeenCalledWith(expect.objectContaining({value: {iterationId: 'it-2'}}));
	});

	it('순서 변경은 afterItemId가 없으면 맨 앞으로 옮긴다', async () => {
		await controller.handleCommand(envelope('REORDER_ITEM', {afterItemId: null}), context);

		expect(api.updatePosition).toHaveBeenCalledWith(expect.objectContaining({afterId: null}));
	});

	it('GitHub가 거부하면 실패를 발행하고 커밋한다', async () => {
		api.updatePosition.mockRejectedValueOnce(new GithubClientError('Could not resolve to a node', 404));

		await controller.handleCommand(envelope('REORDER_ITEM', {afterItemId: 'PVTI_2'}), context);

		expect(resultProducer.send).toHaveBeenCalledWith(expect.objectContaining({
			status: 'FAILED', error: {code: '404', message: 'Could not resolve to a node'},
		}));
		expect(commitOffsets).toHaveBeenCalled();
	});

	it('GitHub 서버 오류는 커밋하지 않고 던진다', async () => {
		api.updatePosition.mockRejectedValueOnce(new Error('GitHub 502'));

		await expect(controller.handleCommand(envelope('REORDER_ITEM', {afterItemId: 'PVTI_2'}), context)).rejects.toThrow('GitHub 502');
		expect(commitOffsets).not.toHaveBeenCalled();
		expect(resultProducer.send).not.toHaveBeenCalled();
	});

	it('payload가 잘못되면 INVALID_PAYLOAD 실패를 발행한다', async () => {
		await controller.handleCommand(envelope('MOVE_ITEM', {singleSelectOptionId: 'opt-done'}), context);

		expect(resultProducer.send).toHaveBeenCalledWith(expect.objectContaining({
			status: 'FAILED', error: {code: 'INVALID_PAYLOAD', message: '잘못된 커맨드 페이로드입니다.'},
		}));
		expect(api.updateFieldValue).not.toHaveBeenCalled();
	});

	it('item이 필요한 커맨드에 itemId가 없으면 INVALID_PAYLOAD 실패를 발행한다', async () => {
		const {itemId: _itemId, ...withoutItem} = envelope('REORDER_ITEM', {afterItemId: null});

		await controller.handleCommand(withoutItem, context);

		expect(resultProducer.send).toHaveBeenCalledWith(expect.objectContaining({status: 'FAILED'}));
		expect(api.updatePosition).not.toHaveBeenCalled();
	});

	it('봉투가 잘못되면 결과 없이 커밋한다', async () => {
		await controller.handleCommand({schemaVersion: 1}, context);

		expect(resultProducer.send).not.toHaveBeenCalled();
		expect(commitOffsets).toHaveBeenCalled();
	});
});
