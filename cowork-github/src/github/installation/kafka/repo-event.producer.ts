import {Injectable as injectable} from '@nestjs/common';
import {KafkaProducerService} from '../../../kafka/kafka-producer.service.js';
import {RepoEvent} from './event/repo-event.js';

const REPO_EVENT_TOPIC = 'github.repo.event';

@injectable()
export class RepoEventProducer {
	constructor(private readonly kafka: KafkaProducerService) {}

	async send(event: RepoEvent): Promise<void> {
		await this.kafka.send(REPO_EVENT_TOPIC, `${event.owner}/${event.repo}`, event);
	}
}
