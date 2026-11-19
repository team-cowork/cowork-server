import {Injectable as injectable} from '@nestjs/common';
import {GithubGraphqlClient} from '../client/github-graphql.client.js';
import {type Nullable} from '../common/nullable.js';
import type {GraphqlProject, GraphqlProjectItem} from './project-board.mapper.js';
import {
	ADD_DRAFT_MUTATION,
	ADD_ITEM_MUTATION,
	CLEAR_ITEM_FIELD_VALUE_MUTATION,
	CONTENT_PROJECT_ITEMS_QUERY,
	CONVERT_DRAFT_MUTATION,
	DELETE_ITEM_MUTATION,
	PROJECT_BY_NODE_QUERY,
	PROJECT_BY_NUMBER_QUERY,
	PROJECT_ITEM_IDS_QUERY,
	PROJECT_ITEM_QUERY,
	PROJECT_ITEMS_QUERY,
	PROJECT_REPOSITORY_QUERY,
	REPLACE_ISSUE_ASSIGNEES_MUTATION,
	UPDATE_DRAFT_MUTATION,
	UPDATE_ITEM_FIELD_VALUE_MUTATION,
	UPDATE_ITEM_POSITION_MUTATION,
} from './project-board.queries.js';

// GitHub Project는 item을 5만 개까지 담을 수 있다. 한 번의 동기화가 rate limit을 다 쓰지 않도록 상한을 둔다.
export const MAX_PROJECT_ITEMS = 5000;

type ItemPage<T> = {node: Nullable<{items?: {pageInfo: {hasNextPage: boolean; endCursor: Nullable<string>}; nodes: Array<Nullable<T>>}}>};

export type ProjectFieldValueInput =
	| {text: string}
	| {number: number}
	| {date: string}
	| {singleSelectOptionId: string}
	| {iterationId: string};

export type ProjectRepository = {
	id: string;
	nameWithOwner: string;
	owner: {__typename: string; login: string};
	issueOrPullRequest?: Nullable<{__typename: string; id: string}>;
};

/**
 GitHub Projects(v2) GraphQL 호출을 모은다. 설치 토큰은 조직 login으로 고른다.
 */
@injectable()
export class ProjectBoardApiService {
	constructor(private readonly graphql: GithubGraphqlClient) {}

	private async listPages<T>(owner: string, query: string, projectNodeId: string): Promise<T[]> {
		const items: T[] = [];
		let after: Nullable<string> = null;
		while (items.length < MAX_PROJECT_ITEMS) {
			// 페이지마다 이전 커서가 필요해 순차로 조회한다.
			// eslint-disable-next-line no-await-in-loop
			const page: ItemPage<T> = await this.graphql.request<ItemPage<T>>(owner, query, {id: projectNodeId, after});
			const connection = page.node?.items;
			if (connection === undefined) {
				return items;
			}

			items.push(...connection.nodes.filter((node): node is T => node !== null));
			if (!connection.pageInfo.hasNextPage || connection.pageInfo.endCursor === null) {
				return items;
			}

			after = connection.pageInfo.endCursor;
		}

		return items.slice(0, MAX_PROJECT_ITEMS);
	}

	/**
	조직 소유 Project만 찾는다. 없으면 null이다.
	*/
	async getProjectByNumber(owner: string, projectNumber: number): Promise<Nullable<GraphqlProject>> {
		const data = await this.graphql.request<{organization: Nullable<{projectV2: Nullable<GraphqlProject>}>}>(
			owner,
			PROJECT_BY_NUMBER_QUERY,
			{owner, number: projectNumber},
		);
		return data.organization?.projectV2 ?? null;
	}

	async getProjectByNode(owner: string, projectNodeId: string): Promise<Nullable<GraphqlProject>> {
		const data = await this.graphql.request<{node: Nullable<GraphqlProject & {__typename?: string}>}>(
			owner,
			PROJECT_BY_NODE_QUERY,
			{id: projectNodeId},
		);
		return data.node?.__typename === 'ProjectV2' ? data.node : null;
	}

	async listItems(owner: string, projectNodeId: string): Promise<GraphqlProjectItem[]> {
		return this.listPages<GraphqlProjectItem>(owner, PROJECT_ITEMS_QUERY, projectNodeId);
	}

	async listItemIds(owner: string, projectNodeId: string): Promise<string[]> {
		const items = await this.listPages<{id: string}>(owner, PROJECT_ITEM_IDS_QUERY, projectNodeId);
		return items.map(item => item.id);
	}

	async getItem(owner: string, itemId: string): Promise<Nullable<GraphqlProjectItem>> {
		const data = await this.graphql.request<{node: Nullable<GraphqlProjectItem & {__typename?: string}>}>(
			owner,
			PROJECT_ITEM_QUERY,
			{id: itemId},
		);
		return data.node?.__typename === 'ProjectV2Item' ? data.node : null;
	}

	async listContentItems(owner: string, contentNodeId: string): Promise<GraphqlProjectItem[]> {
		const data = await this.graphql.request<{node: Nullable<{projectItems?: {nodes: Array<Nullable<GraphqlProjectItem>>}}>}>(
			owner,
			CONTENT_PROJECT_ITEMS_QUERY,
			{id: contentNodeId},
		);
		return (data.node?.projectItems?.nodes ?? []).filter((item): item is GraphqlProjectItem => item !== null);
	}

	async updateFieldValue(parameters: {
		owner: string;
		projectNodeId: string;
		itemId: string;
		fieldId: string;
		value: ProjectFieldValueInput;
	}): Promise<void> {
		const {owner, projectNodeId, itemId, fieldId, value} = parameters;
		await this.graphql.request(owner, UPDATE_ITEM_FIELD_VALUE_MUTATION, {
			projectId: projectNodeId, itemId, fieldId, value,
		});
	}

	async clearFieldValue(parameters: {owner: string; projectNodeId: string; itemId: string; fieldId: string}): Promise<void> {
		const {owner, projectNodeId, itemId, fieldId} = parameters;
		await this.graphql.request(owner, CLEAR_ITEM_FIELD_VALUE_MUTATION, {projectId: projectNodeId, itemId, fieldId});
	}

	/**
	`afterId`가 null이면 item을 Project 전체의 맨 앞으로 옮긴다.
	*/
	async updatePosition(parameters: {owner: string; projectNodeId: string; itemId: string; afterId: Nullable<string>}): Promise<void> {
		const {owner, projectNodeId, itemId, afterId} = parameters;
		await this.graphql.request(owner, UPDATE_ITEM_POSITION_MUTATION, {projectId: projectNodeId, itemId, afterId});
	}

	async getRepository(owner: string, repo: string, number?: number): Promise<Nullable<ProjectRepository>> {
		const data = await this.graphql.request<{repository: Nullable<ProjectRepository>}>(owner, PROJECT_REPOSITORY_QUERY, {
			owner, repo, number: number ?? 1, includeContent: number !== undefined,
		});
		return data.repository;
	}

	async createDraft(owner: string, projectNodeId: string, title: string, body: string): Promise<string> {
		const data = await this.graphql.request<{addProjectV2DraftIssue: {projectItem: {id: string}}}>(
			owner, ADD_DRAFT_MUTATION, {projectId: projectNodeId, title, body},
		);
		return data.addProjectV2DraftIssue.projectItem.id;
	}

	async updateDraft(owner: string, draftIssueId: string, changes: {title?: string; body?: string}): Promise<void> {
		await this.graphql.request(owner, UPDATE_DRAFT_MUTATION, {input: {draftIssueId, ...changes}});
	}

	async addItem(owner: string, projectNodeId: string, contentId: string): Promise<string> {
		const data = await this.graphql.request<{addProjectV2ItemById: {item: {id: string}}}>(
			owner, ADD_ITEM_MUTATION, {projectId: projectNodeId, contentId},
		);
		return data.addProjectV2ItemById.item.id;
	}

	async removeItem(owner: string, projectNodeId: string, itemId: string): Promise<void> {
		await this.graphql.request(owner, DELETE_ITEM_MUTATION, {projectId: projectNodeId, itemId});
	}

	async convertDraft(owner: string, itemId: string, repositoryId: string): Promise<void> {
		await this.graphql.request(owner, CONVERT_DRAFT_MUTATION, {itemId, repositoryId});
	}

	async setAssignees(owner: string, issueId: string, assignees: string[]): Promise<void> {
		await this.graphql.request(owner, REPLACE_ISSUE_ASSIGNEES_MUTATION, {issueId, assignees});
	}
}
