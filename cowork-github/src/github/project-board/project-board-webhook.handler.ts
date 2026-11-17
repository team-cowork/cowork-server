import {Injectable as injectable, Logger} from '@nestjs/common';
import type {GithubWebhookPayload} from '../installation/webhook/github-webhook-payload.js';
import {ProjectBoardSyncService} from './project-board-sync.service.js';

// Item 순서가 바뀌는 action. 순서는 item 레코드에 없어서 Project 레코드를 다시 발행한다.
const ORDER_CHANGING_ACTIONS = new Set(['created', 'deleted', 'archived', 'restored', 'reordered']);
// 이 action은 item 자체가 바뀌므로 item을 다시 읽는다. reordered는 위치만 바뀐다.
const ITEM_CHANGING_ACTIONS = new Set(['created', 'edited', 'archived', 'restored', 'converted']);
// 새 옵션이나 iteration 컬럼이 생기면 field 설정이 바뀌어도 웹훅이 오지 않는다. 이 값이 바뀌면 설정도 다시 읽는다.
const COLUMN_FIELD_TYPES = new Set(['single_select', 'iteration']);
const PROJECT_REFRESH_ACTIONS = new Set(['edited', 'closed', 'reopened']);

/**
 `projects_v2`, `projects_v2_item` 웹훅과 이슈, PR 변경을 Project state로 반영한다. 두 Project 웹훅은 조직 소유
 Project에만 오며, GitHub App에 Organization "Projects" 권한이 있어야 구독할 수 있다.
 */
@injectable()
export class ProjectBoardWebhookHandler {
	private readonly logger = new Logger(ProjectBoardWebhookHandler.name);

	constructor(private readonly syncService: ProjectBoardSyncService) {}

	async handleProjectItem(payload: GithubWebhookPayload): Promise<void> {
		const item = payload.projects_v2_item;
		const owner = payload.organization?.login;
		if (owner === undefined || item?.node_id === undefined || item.project_node_id === undefined) {
			return;
		}

		const action = payload.action ?? '';

		if (action === 'deleted') {
			await this.syncService.publishItemDeleted({
				owner, projectNodeId: item.project_node_id, itemId: item.node_id, updatedAt: item.updated_at,
			});
		} else if (ITEM_CHANGING_ACTIONS.has(action)) {
			await this.syncService.refreshItem(owner, item.node_id);
		}

		const fieldType = payload.changes?.field_value?.field_type;
		if (ORDER_CHANGING_ACTIONS.has(action) || (fieldType !== undefined && COLUMN_FIELD_TYPES.has(fieldType))) {
			this.syncService.scheduleProjectRefresh(owner, item.project_node_id);
		}
	}

	async handleProject(payload: GithubWebhookPayload): Promise<void> {
		const project = payload.projects_v2;
		const owner = payload.organization?.login;
		if (project === undefined || owner === undefined) {
			return;
		}

		const action = payload.action ?? '';

		if (action === 'deleted' && project.number !== undefined) {
			await this.syncService.publishProjectDeleted(owner, project.number);
			return;
		}

		if (PROJECT_REFRESH_ACTIONS.has(action) && project.node_id !== undefined) {
			this.syncService.scheduleProjectRefresh(owner, project.node_id);
		}
	}

	/**
     이슈 웹훅과 PR 웹훅은 content만 알려 준다. 그 content가 들어 있는 Project item을 다시 발행한다. 앱에 Projects 권한이
     없거나 GitHub 호출이 실패해도 이슈 state와 PR state 발행은 막지 않도록 로그만 남긴다.
     */
	async handleContentChanged(owner: string, contentNodeId: string | undefined): Promise<void> {
		if (contentNodeId === undefined) {
			return;
		}

		try {
			await this.syncService.refreshContentItems(owner, contentNodeId);
		} catch (error) {
			this.logger.warn(
				`Failed to refresh GitHub project items for content [content=${contentNodeId}]`,
				error instanceof Error ? error.stack : String(error),
			);
		}
	}
}
