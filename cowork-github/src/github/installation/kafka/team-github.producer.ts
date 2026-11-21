import {Injectable as injectable} from '@nestjs/common';
import {KafkaProducerService} from '../../../kafka/kafka-producer.service.js';
import {
	TeamGithubConnectedEvent,
	TeamGithubDisconnectedEvent,
	TeamGithubRenamedEvent,
} from './event/team-github.event.js';

export type TeamGithubTopic =
	| 'team.github.connected'
	| 'team.github.renamed'
	| 'team.github.disconnected';

@injectable()
export class TeamGithubProducer {
	constructor(private readonly kafka: KafkaProducerService) {}

	async send(
		topic: TeamGithubTopic,
		event: TeamGithubConnectedEvent | TeamGithubRenamedEvent | TeamGithubDisconnectedEvent,
	): Promise<void> {
		await this.kafka.send(topic, event.installationId.toString(), event);
	}
}
