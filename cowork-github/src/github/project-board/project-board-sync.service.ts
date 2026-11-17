import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
} from '@nestjs/common';
import {forEachSequential} from '../common/sequential.js';
import {GithubClientError} from '../github.errors.js';
import type {ProjectAccessError} from './kafka/event/project-state.js';
import {ProjectBoardStateProducer} from './kafka/project-board-state.producer.js';
import {ProjectBoardApiService} from './project-board-api.service.js';
import {
	deletedProjectItemState,
	organizationLogin,
	toProjectItemState,
	toProjectState,
	unavailableProjectState,
} from './project-board.mapper.js';

// 카드를 여러 장 옮기면 웹훅이 연달아 온다. 그동안 Project 설정과 순서를 한 번만 다시 읽도록 잠깐 모은다.
export const PROJECT_REFRESH_DEBOUNCE_MS = 3000;

const accessErrorOf = (error: unknown): ProjectAccessError | undefined => {
	if (!(error instanceof GithubClientError)) {
		return undefined;
	}

	if (error.statusCode === 404) {
		return 'NOT_FOUND';
	}

	return error.statusCode === 401 || error.statusCode === 403 ? 'FORBIDDEN' : undefined;
};

/**
 GitHub Project의 설정, item, 순서를 state 토픽으로 발행한다. 전체 동기화는 `github.sync.command.v1`의 `PROJECT` 대상으로
 실행하고, 웹훅은 바뀐 item과 Project 순서만 다시 읽는다.
 */
@injectable()
export class ProjectBoardSyncService implements OnModuleDestroy {
	private readonly logger = new Logger(ProjectBoardSyncService.name);
	private readonly pendingRefreshes = new Map<string, ReturnType<typeof setTimeout>>();

	constructor(
		private readonly api: ProjectBoardApiService,
		private readonly producer: ProjectBoardStateProducer,
	) {}

	private async publishUnavailable(
		owner: string,
		projectNumber: number,
		accessError: ProjectAccessError,
		sourceOccurredAt: string,
	): Promise<void> {
		await this.producer.sendProject(unavailableProjectState({
			owner, projectNumber, accessError, deleted: false, sourceOccurredAt,
		}));
		await this.producer.sendSyncCompleted(owner, projectNumber);
	}

	onModuleDestroy() {
		for (const timer of this.pendingRefreshes.values()) {
			clearTimeout(timer);
		}

		this.pendingRefreshes.clear();
	}

	/**
     Project 하나를 처음부터 다시 발행한다. Project를 찾을 수 없거나 권한이 없으면 그 사실을 Project 레코드로 남기고
     완료 마커도 보낸다. 조회가 `503`으로 기다리지 않고 원인을 바로 알리게 하기 위해서다.
     */
	async syncProject(owner: string, projectNumber: number): Promise<void> {
		const startedAt = new Date().toISOString();
		try {
			const project = await this.api.getProjectByNumber(owner, projectNumber);
			if (project === null) {
				await this.publishUnavailable(owner, projectNumber, 'NOT_FOUND', startedAt);
				return;
			}

			const items = await this.api.listItems(owner, project.id);
			// Item마다 레코드가 하나라 순서대로 보내 broker 부하를 고르게 한다.
			await forEachSequential(items, async item => this.producer.sendItem(toProjectItemState(item, owner)));
			await this.producer.sendProject(toProjectState(owner, project, items.map(item => item.id), startedAt));
		} catch (error) {
			const accessError = accessErrorOf(error);
			if (accessError === undefined) {
				throw error;
			}

			this.logger.warn(`Cannot read GitHub project [project=${owner}/${projectNumber}, reason=${accessError}]`);
			await this.publishUnavailable(owner, projectNumber, accessError, startedAt);
			return;
		}

		await this.producer.sendSyncCompleted(owner, projectNumber);
		this.logger.log(`Finished GitHub project sync [project=${owner}/${projectNumber}]`);
	}

	/**
	Project 설정(field, view)과 item 순서만 다시 읽는다. item 내용은 각 item 웹훅이 따로 발행한다.
	*/
	async refreshProject(owner: string, projectNodeId: string): Promise<void> {
		const startedAt = new Date().toISOString();
		const project = await this.api.getProjectByNode(owner, projectNodeId);
		if (project === null) {
			return;
		}

		const projectOwner = organizationLogin(project.owner) ?? owner;
		const itemIds = await this.api.listItemIds(projectOwner, project.id);
		await this.producer.sendProject(toProjectState(projectOwner, project, itemIds, startedAt));
	}

	/**
	같은 Project의 새로 고침 요청을 잠깐 모아 한 번만 실행한다. 실패는 로그만 남기고, 다음 주기 동기화가 바로잡는다.
	*/
	scheduleProjectRefresh(owner: string, projectNodeId: string): void {
		if (this.pendingRefreshes.has(projectNodeId)) {
			return;
		}

		const timer = setTimeout(() => {
			this.pendingRefreshes.delete(projectNodeId);
			this.refreshProject(owner, projectNodeId).catch((error: unknown) => {
				this.logger.error(
					`Failed to refresh GitHub project [project=${projectNodeId}]`,
					error instanceof Error ? error.stack : String(error),
				);
			});
		}, PROJECT_REFRESH_DEBOUNCE_MS);
		timer.unref();
		this.pendingRefreshes.set(projectNodeId, timer);
	}

	/**
	Item 하나를 다시 읽어 발행한다. 이미 지워진 item이면 아무것도 하지 않는다. 삭제 웹훅이 따로 온다.
	*/
	async refreshItem(owner: string, itemId: string): Promise<void> {
		try {
			const item = await this.api.getItem(owner, itemId);
			if (item !== null) {
				await this.producer.sendItem(toProjectItemState(item, owner));
			}
		} catch (error) {
			if (accessErrorOf(error) !== 'NOT_FOUND') {
				throw error;
			}
		}
	}

	/**
	이슈나 PR의 제목, 담당자, 라벨이 바뀌면 그 content가 들어 있는 모든 Project item을 다시 발행한다.
	*/
	async refreshContentItems(owner: string, contentNodeId: string): Promise<void> {
		const items = await this.api.listContentItems(owner, contentNodeId);
		await forEachSequential(items, async item => this.producer.sendItem(toProjectItemState(item, owner)));
	}

	async publishItemDeleted(parameters: {owner: string; projectNodeId: string; itemId: string; updatedAt?: string}): Promise<void> {
		const now = new Date().toISOString();
		const {updatedAt} = parameters;
		// 삭제 웹훅의 updated_at은 마지막 변경 시각이라, 받은 시각과 비교해 늦은 쪽을 버전으로 쓴다.
		const sourceOccurredAt = updatedAt !== undefined && Date.parse(updatedAt) > Date.parse(now) ? updatedAt : now;
		await this.producer.sendItem(deletedProjectItemState({
			owner: parameters.owner, projectNodeId: parameters.projectNodeId, itemId: parameters.itemId, sourceOccurredAt,
		}));
	}

	async publishProjectDeleted(owner: string, projectNumber: number): Promise<void> {
		await this.producer.sendProject(unavailableProjectState({
			owner, projectNumber, accessError: 'NOT_FOUND', deleted: true, sourceOccurredAt: new Date().toISOString(),
		}));
	}
}
