import type {KafkaProducerService} from '../../kafka/kafka-producer.service.js';
import {type IssueWriteResultEvent} from './event/issue-write-result.event.js';
import {IssueWriteCommandType} from './issue-write-command-type.js';
import {IssueWriteResultProducer} from './issue-write-result.producer.js';

describe('IssueWriteResultProducer', () => {
	let producer: IssueWriteResultProducer;
	let mockSend: jest.Mock;

	beforeEach(() => {
		mockSend = jest.fn().mockResolvedValue(undefined);
		producer = new IssueWriteResultProducer({send: mockSend} as unknown as KafkaProducerService);
	});

	it('github.issue-write.result.v1 토픽에 operationId를 key로 발행한다', async () => {
		const event: IssueWriteResultEvent = {
			schemaVersion: 1,
			operationId: 'op-1',
			idempotencyKey: 'idem-1',
			commandType: IssueWriteCommandType.CREATE_COMMENT,
			status: 'SUCCEEDED',
			result: {id: 100, author: 'octocat', body: 'hi'},
			occurredAt: '2026-09-26T00:00:00.000Z',
		};

		await producer.send(event);

		expect(mockSend).toHaveBeenCalledWith('github.issue-write.result.v1', 'op-1', event);
	});
});
