import {type Nullable} from '../common/nullable.js';
import {toRepoIdentity} from '../installation/kafka/event/repo-identity.js';
import type {
	ProjectItemContentState,
	ProjectItemFieldValueState,
	ProjectItemStateEvent,
} from './kafka/event/project-item-state.js';
import type {
	ProjectAccessError,
	ProjectFieldState,
	ProjectStateEvent,
	ProjectViewState,
} from './kafka/event/project-state.js';

type Connection<T> = {nodes?: Array<Nullable<T>>};

type FieldRef = {id?: string; name?: string};

type IterationNode = {id: string; title: string; startDate: string; duration: number};

export type GraphqlProjectField = {
	__typename?: string;
	id?: string;
	name?: string;
	dataType?: string;
	options?: Array<{id: string; name: string; color: string; description?: string}>;
	configuration?: {iterations?: IterationNode[]; completedIterations?: IterationNode[]};
};

export type GraphqlProjectView = {
	id: string;
	number: number;
	name: string;
	layout: string;
	filter?: Nullable<string>;
	fields?: Nullable<Connection<FieldRef>>;
	groupByFields?: Nullable<Connection<FieldRef>>;
	verticalGroupByFields?: Nullable<Connection<FieldRef>>;
	sortByFields?: Nullable<Connection<{direction: string; field?: FieldRef}>>;
};

export type GraphqlProject = {
	id: string;
	number: number;
	title: string;
	url: string;
	closed: boolean;
	owner?: {__typename?: string; login?: string};
	fields?: Connection<GraphqlProjectField>;
	views?: Connection<GraphqlProjectView>;
};

type GraphqlActorConnection = Connection<{login: string}>;

export type GraphqlProjectItemContent = {
	__typename?: string;
	id?: string;
	number?: number;
	title?: string;
	body?: string;
	state?: string;
	isDraft?: boolean;
	url?: string;
	updatedAt?: string;
	repository?: {nameWithOwner?: string};
	assignees?: GraphqlActorConnection;
	labels?: Connection<{name: string; color: string}>;
	milestone?: Nullable<{title?: string}>;
};

export type GraphqlProjectItemFieldValue = {
	__typename?: string;
	text?: Nullable<string>;
	number?: Nullable<number>;
	date?: Nullable<string>;
	optionId?: Nullable<string>;
	iterationId?: Nullable<string>;
	field?: FieldRef;
};

export type GraphqlProjectItem = {
	id: string;
	type: ProjectItemStateEvent['type'];
	isArchived: boolean;
	updatedAt: string;
	project: {id: string; number: number; owner?: {__typename?: string; login?: string}};
	content?: Nullable<GraphqlProjectItemContent>;
	fieldValues?: Connection<GraphqlProjectItemFieldValue>;
};

const presentNodes = <T>(connection: Nullable<Connection<T>> | undefined): T[] =>
	(connection?.nodes ?? []).filter((node): node is T => node !== null && node !== undefined);

const fieldIds = (connection: Nullable<Connection<FieldRef>> | undefined): string[] =>
	presentNodes(connection).map(field => field.id).filter((id): id is string => id !== undefined);

/**
조직이 소유하지 않은 Project의 owner는 null이다. 개인 계정 Project는 설치 토큰으로 다룰 수 없어 연결 대상이 아니다.
*/
export const organizationLogin = (owner: {__typename?: string; login?: string} | undefined): Nullable<string> =>
	owner?.__typename === 'Organization' && owner.login !== undefined ? toRepoIdentity(owner.login) : null;

export const toFieldState = (field: GraphqlProjectField): ProjectFieldState | undefined => {
	if (field.id === undefined || field.name === undefined) {
		return undefined;
	}

	const iterations = [
		...(field.configuration?.iterations ?? []).map(iteration => ({...iteration, completed: false})),
		...(field.configuration?.completedIterations ?? []).map(iteration => ({...iteration, completed: true})),
	].toSorted((left, right) => left.startDate.localeCompare(right.startDate));
	return {
		id: field.id,
		name: field.name,
		dataType: field.dataType ?? 'UNKNOWN',
		options: (field.options ?? []).map(option => ({
			id: option.id, name: option.name, color: option.color, description: option.description ?? '',
		})),
		iterations,
	};
};

export const toViewState = (view: GraphqlProjectView): ProjectViewState => ({
	id: view.id,
	number: view.number,
	name: view.name,
	layout: view.layout,
	filter: view.filter === undefined || view.filter === '' ? null : view.filter,
	visibleFieldIds: fieldIds(view.fields),
	groupByFieldIds: fieldIds(view.groupByFields),
	verticalGroupByFieldIds: fieldIds(view.verticalGroupByFields),
	sortBy: presentNodes(view.sortByFields)
		.filter(sort => sort.field?.id !== undefined)
		.map(sort => ({fieldId: sort.field!.id!, direction: sort.direction})),
});

export const toProjectState = (
	owner: string,
	project: GraphqlProject,
	itemIds: string[],
	sourceOccurredAt: string,
): ProjectStateEvent => ({
	owner: toRepoIdentity(owner),
	projectNumber: project.number,
	nodeId: project.id,
	title: project.title,
	url: project.url,
	closed: project.closed,
	fields: presentNodes(project.fields)
		.map(field => toFieldState(field))
		.filter((field): field is ProjectFieldState => field !== undefined),
	views: presentNodes(project.views).map(view => toViewState(view)),
	itemIds,
	accessError: null,
	deleted: false,
	sourceOccurredAt,
});

export const unavailableProjectState = (parameters: {
	owner: string;
	projectNumber: number;
	accessError: Nullable<ProjectAccessError>;
	deleted: boolean;
	sourceOccurredAt: string;
}): ProjectStateEvent => ({
	owner: toRepoIdentity(parameters.owner),
	projectNumber: parameters.projectNumber,
	nodeId: null,
	title: '',
	url: '',
	closed: false,
	fields: [],
	views: [],
	itemIds: [],
	accessError: parameters.accessError,
	deleted: parameters.deleted,
	sourceOccurredAt: parameters.sourceOccurredAt,
});

const emptyValue = {
	text: null, number: null, date: null, optionId: null, iterationId: null,
} as const;

type FieldValuePart = Pick<ProjectItemFieldValueState, 'dataType'> & Partial<ProjectItemFieldValueState>;

const FIELD_VALUE_BUILDERS = new Map<string, (value: GraphqlProjectItemFieldValue) => FieldValuePart>([
	['ProjectV2ItemFieldTextValue', value => ({dataType: 'TEXT', text: value.text ?? null})],
	['ProjectV2ItemFieldNumberValue', value => ({dataType: 'NUMBER', number: value.number ?? null})],
	['ProjectV2ItemFieldDateValue', value => ({dataType: 'DATE', date: value.date ?? null})],
	['ProjectV2ItemFieldSingleSelectValue', value => ({dataType: 'SINGLE_SELECT', optionId: value.optionId ?? null})],
	['ProjectV2ItemFieldIterationValue', value => ({dataType: 'ITERATION', iterationId: value.iterationId ?? null})],
]);

export const toFieldValueState = (value: GraphqlProjectItemFieldValue): ProjectItemFieldValueState | undefined => {
	const fieldId = value.field?.id;
	const fieldName = value.field?.name;
	if (fieldId === undefined || fieldName === undefined) {
		return undefined;
	}

	// 담당자, 라벨, 저장소 같은 그 밖의 값은 content에서 따로 읽는다.
	const toValue = value.__typename === undefined ? undefined : FIELD_VALUE_BUILDERS.get(value.__typename);
	return toValue === undefined
		? undefined
		: {
			fieldId, fieldName, ...emptyValue, ...toValue(value),
		};
};

const toContentState = (content: GraphqlProjectItemContent): ProjectItemContentState => ({
	title: content.title ?? '',
	...(content.body !== undefined && {body: content.body.replace(/\n\n<!-- cowork-project-draft:[\d\-a-f]{36} -->$/iv, '')}),
	number: content.number ?? null,
	state: content.state ?? null,
	url: content.url ?? null,
	repository: content.repository?.nameWithOwner ?? null,
	assignees: presentNodes(content.assignees).map(assignee => assignee.login),
	labels: presentNodes(content.labels).map(label => ({name: label.name, color: label.color})),
	milestone: content.milestone?.title ?? null,
	draft: content.__typename === 'DraftIssue' || content.isDraft === true,
});

const latest = (first: string, second: string | undefined): string =>
	second !== undefined && Date.parse(second) > Date.parse(first) ? second : first;

export const toProjectItemState = (item: GraphqlProjectItem, fallbackOwner: string): ProjectItemStateEvent => {
	const content = item.content ?? null;
	return {
		owner: organizationLogin(item.project.owner) ?? toRepoIdentity(fallbackOwner),
		projectNodeId: item.project.id,
		projectNumber: item.project.number,
		itemId: item.id,
		type: item.type,
		contentNodeId: content?.id ?? null,
		content: content === null ? null : toContentState(content),
		fieldValues: presentNodes(item.fieldValues)
			.map(value => toFieldValueState(value))
			.filter((value): value is ProjectItemFieldValueState => value !== undefined),
		archived: item.isArchived,
		deleted: false,
		sourceOccurredAt: latest(item.updatedAt, content?.updatedAt),
	};
};

export const deletedProjectItemState = (parameters: {
	owner: string;
	projectNodeId: string;
	itemId: string;
	sourceOccurredAt: string;
}): ProjectItemStateEvent => ({
	owner: toRepoIdentity(parameters.owner),
	projectNodeId: parameters.projectNodeId,
	projectNumber: null,
	itemId: parameters.itemId,
	type: 'REDACTED',
	contentNodeId: null,
	content: null,
	fieldValues: [],
	archived: false,
	deleted: true,
	sourceOccurredAt: parameters.sourceOccurredAt,
});
