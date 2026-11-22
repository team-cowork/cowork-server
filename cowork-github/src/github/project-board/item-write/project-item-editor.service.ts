import {createHash} from 'node:crypto';
import {Injectable as injectable} from '@nestjs/common';
import {GithubClientError, GithubRateLimitError} from '../../github.errors.js';
import {type Nullable} from '../../common/nullable.js';
import {ProjectBoardApiService, type ProjectFieldValueInput, type ProjectRepository} from '../project-board-api.service.js';
import {type GraphqlProject, type GraphqlProjectItem, organizationLogin} from '../project-board.mapper.js';
import type {
	AddItemPayloadDto,
	CreateDraftPayloadDto,
	SetAssigneesPayloadDto,
	SetFieldPayloadDto,
	UpdateDraftPayloadDto,
} from './dto/project-item-write-payload.dto.js';
import {ProjectDraftHistory} from './project-draft-history.js';

export type ProjectItemTarget = {owner: string; projectNodeId: string; itemId?: Nullable<string>};
export type ProjectItemWriteResult = {itemId: string};

const draftMarker = (operationId: string): string => `<!-- cowork-project-draft:${operationId} -->`;

@injectable()
export class ProjectItemEditorService {
	constructor(private readonly api: ProjectBoardApiService, private readonly history: ProjectDraftHistory) {}

	private async requireProject(target: ProjectItemTarget): Promise<GraphqlProject> {
		const project = await this.api.getProjectByNode(target.owner, target.projectNodeId);
		if (project === null) {
			throw new GithubClientError('GitHub Project를 찾을 수 없습니다.', 404);
		}

		if (organizationLogin(project.owner) !== target.owner.toLowerCase()) {
			throw new GithubClientError('팀에 연결된 조직의 Project만 수정할 수 있습니다.', 403);
		}

		return project;
	}

	private async requireItem(target: ProjectItemTarget): Promise<GraphqlProjectItem> {
		if (typeof target.itemId !== 'string' || target.itemId.length === 0) {
			throw new GithubClientError('카드 ID가 필요합니다.', 400);
		}

		const item = await this.api.getItem(target.owner, target.itemId);
		if (item?.project.id !== target.projectNodeId) {
			throw new GithubClientError('보드에서 카드를 찾을 수 없습니다.', 404);
		}

		return item;
	}

	private async requireRepository(target: ProjectItemTarget, repo: string, number?: number): Promise<ProjectRepository> {
		const repository = await this.api.getRepository(target.owner, repo, number);
		if (repository === null) {
			throw new GithubClientError('저장소를 찾을 수 없습니다.', 404);
		}

		if (repository.owner.__typename !== 'Organization'
			|| repository.nameWithOwner.toLowerCase() !== `${target.owner}/${repo}`.toLowerCase()) {
			throw new GithubClientError('팀에 연결된 조직의 저장소만 사용할 수 있습니다.', 403);
		}

		return repository;
	}

	private requireContentRepository(target: ProjectItemTarget, item: GraphqlProjectItem, repo: string): void {
		if (item.content?.repository?.nameWithOwner?.toLowerCase() !== `${target.owner}/${repo}`.toLowerCase()) {
			throw new GithubClientError('카드의 저장소가 요청한 저장소와 다릅니다.', 403);
		}
	}

	async setField(target: ProjectItemTarget, payload: SetFieldPayloadDto): Promise<void> {
		const project = await this.requireProject(target);
		const item = await this.requireItem(target);
		const field = project.fields?.nodes?.find(field => field?.id === payload.fieldId);
		const values = Object.entries(payload.value).filter(([, value]) => value !== undefined && value !== null);
		const keys = new Map([['TEXT', 'text'], ['NUMBER', 'number'], ['DATE', 'date'], ['SINGLE_SELECT', 'singleSelectOptionId'], ['ITERATION', 'iterationId']]);
		if (field === undefined || field === null || values.length !== 1 || keys.get(field.dataType ?? '') !== values[0][0]) {
			throw new GithubClientError('필드 타입에 맞는 값 하나를 지정해 주십시오.', 400);
		}

		if (field.dataType === 'SINGLE_SELECT' && !field.options?.some(option => option.id === payload.value.singleSelectOptionId)) {
			throw new GithubClientError('필드의 선택 항목을 찾을 수 없습니다.', 400);
		}

		const iterations = [...(field.configuration?.iterations ?? []), ...(field.configuration?.completedIterations ?? [])];
		if (field.dataType === 'ITERATION' && iterations.every(iteration => iteration.id !== payload.value.iterationId)) {
			throw new GithubClientError('필드의 반복 주기를 찾을 수 없습니다.', 400);
		}

		await this.api.updateFieldValue({
			owner: target.owner, projectNodeId: target.projectNodeId, itemId: item.id,
			fieldId: payload.fieldId, value: Object.fromEntries(values) as ProjectFieldValueInput,
		});
	}

	async clearField(target: ProjectItemTarget, fieldId: string): Promise<void> {
		const project = await this.requireProject(target);
		const item = await this.requireItem(target);
		const field = project.fields?.nodes?.find(field => field?.id === fieldId);
		if (!['TEXT', 'NUMBER', 'DATE', 'SINGLE_SELECT', 'ITERATION'].includes(field?.dataType ?? '')) {
			throw new GithubClientError('편집할 수 없는 필드입니다.', 400);
		}

		await this.api.clearFieldValue({
			owner: target.owner, projectNodeId: target.projectNodeId, itemId: item.id, fieldId,
		});
	}

	async createDraft(target: ProjectItemTarget, payload: CreateDraftPayloadDto, operationId: string): Promise<ProjectItemWriteResult> {
		await this.requireProject(target);
		if (/^https?:\/\/github\.com\/\S+$/iv.test(payload.title.trim())) {
			throw new GithubClientError('이슈 URL은 기존 카드 추가 기능으로 사용해 주십시오.', 400);
		}

		const fingerprint = createHash('sha256').update(JSON.stringify([target.owner, target.projectNodeId, payload.title, payload.body])).digest('hex');
		const {record, claimed} = await this.history.claim(operationId, fingerprint);
		if (record.fingerprint !== fingerprint) {
			throw new Error('Project draft operation reused with a different payload');
		}

		if (record.error !== undefined) {
			throw new GithubClientError(record.error.message, record.error.statusCode);
		}

		if (record.itemId !== undefined) {
			return {itemId: record.itemId};
		}

		try {
			let itemId: string;
			if (claimed) {
				itemId = await this.api.createDraft(target.owner, target.projectNodeId, payload.title, `${payload.body}\n\n${draftMarker(operationId)}`)
					.catch(async (error: unknown) => {
						if (error instanceof GithubRateLimitError) {
							await this.history.markRetryable(operationId, record);
						}

						throw error;
					});
			} else {
				const items = await this.api.listItems(target.owner, target.projectNodeId);
				const found = items.find(item => item.content?.body?.includes(draftMarker(operationId)) === true);
				if (found === undefined) {
					// GitHub 응답을 잃은 생성은 재실행하지 않는다. 조회 상한이나 사용자의 삭제도 원인일 수 있다.
					throw new GithubClientError('Draft 생성 결과를 확인할 수 없습니다. 보드를 확인한 뒤 다시 요청해 주십시오.', 409);
				}

				itemId = found.id;
			}

			await this.history.save(operationId, {...record, itemId});
			return {itemId};
		} catch (error) {
			if (error instanceof GithubClientError) {
				await this.history.save(operationId, {...record, error: {message: error.message, statusCode: error.statusCode}});
			}

			throw error;
		}
	}

	async updateDraft(target: ProjectItemTarget, payload: UpdateDraftPayloadDto): Promise<void> {
		await this.requireProject(target);
		const item = await this.requireItem(target);
		if (item.type !== 'DRAFT_ISSUE' || (item.content?.id === undefined || item.content.id.length === 0)) {
			throw new GithubClientError('Draft 카드만 수정할 수 있습니다.', 400);
		}

		const changes = {
			...(typeof payload.title === 'string' && {title: payload.title}),
			...(typeof payload.body === 'string' && {body: payload.body}),
		};
		if (Object.keys(changes).length === 0) {
			throw new GithubClientError('수정할 제목 또는 본문을 지정해 주십시오.', 400);
		}

		await this.api.updateDraft(target.owner, item.content.id, changes);
	}

	async addItem(target: ProjectItemTarget, payload: AddItemPayloadDto): Promise<ProjectItemWriteResult> {
		await this.requireProject(target);
		const repository = await this.requireRepository(target, payload.repo, payload.number);
		const content = repository.issueOrPullRequest;
		if (content === undefined || content === null || !['Issue', 'PullRequest'].includes(content.__typename)) {
			throw new GithubClientError('저장소에서 이슈 또는 PR을 찾을 수 없습니다.', 404);
		}

		return {itemId: await this.api.addItem(target.owner, target.projectNodeId, content.id)};
	}

	async removeItem(target: ProjectItemTarget): Promise<void> {
		await this.requireProject(target);
		if (typeof target.itemId !== 'string' || target.itemId.length === 0) {
			throw new GithubClientError('카드 ID가 필요합니다.', 400);
		}

		// 삭제 후 결과 발행에 실패한 재전달도 성공으로 수렴한다.
		const item = await this.api.getItem(target.owner, target.itemId).catch((error: unknown) => {
			if (error instanceof GithubClientError && error.statusCode === 404) {
				return null;
			}

			throw error;
		});
		if (item === null) {
			return;
		}

		if (item.project.id !== target.projectNodeId) {
			throw new GithubClientError('보드에서 카드를 찾을 수 없습니다.', 404);
		}

		await this.api.removeItem(target.owner, target.projectNodeId, item.id);
	}

	async convertDraft(target: ProjectItemTarget, repo: string): Promise<ProjectItemWriteResult> {
		await this.requireProject(target);
		const item = await this.requireItem(target);
		const repository = await this.requireRepository(target, repo);
		if (item.type === 'ISSUE') {
			this.requireContentRepository(target, item, repo);
			return {itemId: item.id};
		}

		if (item.type !== 'DRAFT_ISSUE') {
			throw new GithubClientError('Draft 카드만 이슈로 전환할 수 있습니다.', 400);
		}

		await this.api.convertDraft(target.owner, item.id, repository.id);
		return {itemId: item.id};
	}

	async setAssignees(target: ProjectItemTarget, payload: SetAssigneesPayloadDto): Promise<void> {
		await this.requireProject(target);
		const item = await this.requireItem(target);
		if (item.type !== 'ISSUE' || (item.content?.id === undefined || item.content.id.length === 0)) {
			throw new GithubClientError('이슈 카드의 담당자만 변경할 수 있습니다.', 400);
		}

		this.requireContentRepository(target, item, payload.repo);
		await this.api.setAssignees(target.owner, item.content.id, payload.assignees);
	}
}
