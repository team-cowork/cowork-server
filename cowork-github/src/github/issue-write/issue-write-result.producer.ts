import {Injectable as injectable} from '@nestjs/common';
import {KafkaProducerService} from '../../kafka/kafka-producer.service.js';
import {IssueWriteResultEvent} from './event/issue-write-result.event.js';
import {ISSUE_WRITE_RESULT_TOPIC} from './issue-write-topics.js';

@injectable()
export class IssueWriteResultProducer {
	constructor(private readonly kafka: KafkaProducerService) {}

	async send(event: IssueWriteResultEvent): Promise<void> {
		await this.kafka.send(ISSUE_WRITE_RESULT_TOPIC, event.operationId, event);
	}
}
