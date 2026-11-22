import {Injectable as injectable} from '@nestjs/common';
import {KafkaProducerService} from '../../../kafka/kafka-producer.service.js';
import {toRepoIdentity} from '../../installation/kafka/event/repo-identity.js';
import type {ProjectItemStateEvent} from './event/project-item-state.js';
import type {ProjectStateEvent} from './event/project-state.js';

export const PROJECT_STATE_TOPIC = 'github.project.state.v1';
export const PROJECT_ITEM_STATE_TOPIC = 'github.project-item.state.v1';

/**
 Project 동기화 완료 마커. 대상 Project의 item과 Project 레코드를 모두 보낸 뒤 두 state 토픽에 마지막으로 보낸다.
 저장소 마커와 같은 `recordType`을 쓰고, `repo` 없이 `projectNumber`로 대상을 구분한다.
 */
export type ProjectSyncCompletedMarker = {
	recordType: 'SYNC_COMPLETED';
	owner: string;
	projectNumber: number;
	completedAt: string;
};

export const projectSyncCompletedMarker = (owner: string, projectNumber: number): ProjectSyncCompletedMarker => ({
	recordType: 'SYNC_COMPLETED',
	owner: toRepoIdentity(owner),
	projectNumber,
	completedAt: new Date().toISOString(),
});

export const projectStateKey = (owner: string, projectNumber: number): string =>
	`${toRepoIdentity(owner)}/${projectNumber}`;

@injectable()
export class ProjectBoardStateProducer {
	constructor(private readonly kafka: KafkaProducerService) {}

	async sendProject(event: ProjectStateEvent): Promise<void> {
		await this.kafka.send(PROJECT_STATE_TOPIC, projectStateKey(event.owner, event.projectNumber), event);
	}

	async sendItem(event: ProjectItemStateEvent): Promise<void> {
		await this.kafka.send(PROJECT_ITEM_STATE_TOPIC, event.itemId, event);
	}

	// Item 토픽 마커를 먼저 보내야 Project 마커가 남았을 때 item도 모두 반영되었다고 볼 수 있다.
	async sendSyncCompleted(owner: string, projectNumber: number): Promise<void> {
		const marker = projectSyncCompletedMarker(owner, projectNumber);
		const key = `sync:${marker.owner}#${projectNumber}`;
		await this.kafka.send(PROJECT_ITEM_STATE_TOPIC, key, marker);
		await this.kafka.send(PROJECT_STATE_TOPIC, key, marker);
	}
}
