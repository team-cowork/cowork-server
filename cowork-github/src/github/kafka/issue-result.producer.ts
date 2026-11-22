import {Injectable as injectable} from '@nestjs/common';
import {KafkaProducerService} from '../../kafka/kafka-producer.service.js';
import {IssueResultEvent} from './event/issue-result.event.js';

@injectable()
export class IssueResultProducer {
	constructor(private readonly kafka: KafkaProducerService) {}

	async send(event: IssueResultEvent): Promise<void> {
		await this.kafka.send('github.issue.result', event.channelId.toString(), event);
	}
}
