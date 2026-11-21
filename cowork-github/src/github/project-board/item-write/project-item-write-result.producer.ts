import {Injectable as injectable} from '@nestjs/common';
import {KafkaProducerService} from '../../../kafka/kafka-producer.service.js';
import {type ProjectItemWriteResultEvent} from './project-item-write-result.event.js';
import {PROJECT_ITEM_WRITE_RESULT_TOPIC} from './project-item-write-topics.js';

@injectable()
export class ProjectItemWriteResultProducer {
	constructor(private readonly kafka: KafkaProducerService) {}

	async send(event: ProjectItemWriteResultEvent): Promise<void> {
		await this.kafka.send(PROJECT_ITEM_WRITE_RESULT_TOPIC, event.operationId, event);
	}
}
