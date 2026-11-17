// GitHub Projects(v2) GraphQL 문서. 필드 이름은 https://docs.github.com/en/graphql/reference/projects 를 따른다.

const fieldCommon = '... on ProjectV2FieldCommon { id name }';

export const PROJECT_FIELDS_FRAGMENT = `
fragment ProjectFields on ProjectV2 {
	id
	number
	title
	url
	closed
	owner { __typename ... on Organization { login } }
	fields(first: 100) {
		nodes {
			__typename
			... on ProjectV2FieldCommon { id name dataType }
			... on ProjectV2SingleSelectField { options { id name color description } }
			... on ProjectV2IterationField {
				configuration {
					iterations { id title startDate duration }
					completedIterations { id title startDate duration }
				}
			}
		}
	}
	views(first: 50) {
		nodes {
			id
			number
			name
			layout
			filter
			fields(first: 50) { nodes { ${fieldCommon} } }
			groupByFields(first: 5) { nodes { ${fieldCommon} } }
			verticalGroupByFields(first: 5) { nodes { ${fieldCommon} } }
			sortByFields(first: 5) { nodes { direction field { ${fieldCommon} } } }
		}
	}
}`;

export const PROJECT_ITEM_FRAGMENT = `
fragment ProjectItemFields on ProjectV2Item {
	id
	type
	isArchived
	updatedAt
	project { id number owner { __typename ... on Organization { login } } }
	content {
		__typename
		... on DraftIssue { id title updatedAt assignees(first: 20) { nodes { login } } }
		... on Issue {
			id number title state url updatedAt
			repository { nameWithOwner }
			assignees(first: 20) { nodes { login } }
			labels(first: 30) { nodes { name color } }
			milestone { title }
		}
		... on PullRequest {
			id number title state isDraft url updatedAt
			repository { nameWithOwner }
			assignees(first: 20) { nodes { login } }
			labels(first: 30) { nodes { name color } }
			milestone { title }
		}
	}
	fieldValues(first: 50) {
		nodes {
			__typename
			... on ProjectV2ItemFieldTextValue { text field { ${fieldCommon} } }
			... on ProjectV2ItemFieldNumberValue { number field { ${fieldCommon} } }
			... on ProjectV2ItemFieldDateValue { date field { ${fieldCommon} } }
			... on ProjectV2ItemFieldSingleSelectValue { optionId field { ${fieldCommon} } }
			... on ProjectV2ItemFieldIterationValue { iterationId field { ${fieldCommon} } }
		}
	}
}`;

export const PROJECT_BY_NUMBER_QUERY = `
query ProjectByNumber($owner: String!, $number: Int!) {
	organization(login: $owner) { projectV2(number: $number) { ...ProjectFields } }
}
${PROJECT_FIELDS_FRAGMENT}`;

export const PROJECT_BY_NODE_QUERY = `
query ProjectByNode($id: ID!) {
	node(id: $id) { __typename ... on ProjectV2 { ...ProjectFields } }
}
${PROJECT_FIELDS_FRAGMENT}`;

export const PROJECT_ITEMS_QUERY = `
query ProjectItems($id: ID!, $after: String) {
	node(id: $id) {
		... on ProjectV2 {
			items(first: 100, after: $after, orderBy: {field: POSITION, direction: ASC}) {
				pageInfo { hasNextPage endCursor }
				nodes { ...ProjectItemFields }
			}
		}
	}
}
${PROJECT_ITEM_FRAGMENT}`;

export const PROJECT_ITEM_IDS_QUERY = `
query ProjectItemIds($id: ID!, $after: String) {
	node(id: $id) {
		... on ProjectV2 {
			items(first: 100, after: $after, orderBy: {field: POSITION, direction: ASC}) {
				pageInfo { hasNextPage endCursor }
				nodes { id }
			}
		}
	}
}`;

export const PROJECT_ITEM_QUERY = `
query ProjectItem($id: ID!) {
	node(id: $id) { __typename ...ProjectItemFields }
}
${PROJECT_ITEM_FRAGMENT}`;

export const CONTENT_PROJECT_ITEMS_QUERY = `
query ContentProjectItems($id: ID!) {
	node(id: $id) {
		... on Issue { projectItems(first: 50, includeArchived: true) { nodes { ...ProjectItemFields } } }
		... on PullRequest { projectItems(first: 50, includeArchived: true) { nodes { ...ProjectItemFields } } }
	}
}
${PROJECT_ITEM_FRAGMENT}`;

export const UPDATE_ITEM_FIELD_VALUE_MUTATION = `
mutation UpdateItemFieldValue($projectId: ID!, $itemId: ID!, $fieldId: ID!, $value: ProjectV2FieldValue!) {
	updateProjectV2ItemFieldValue(input: {projectId: $projectId, itemId: $itemId, fieldId: $fieldId, value: $value }) {
		projectV2Item { id }
	}
}`;

export const CLEAR_ITEM_FIELD_VALUE_MUTATION = `
mutation ClearItemFieldValue($projectId: ID!, $itemId: ID!, $fieldId: ID!) {
	clearProjectV2ItemFieldValue(input: {projectId: $projectId, itemId: $itemId, fieldId: $fieldId }) {
		projectV2Item { id }
	}
}`;

export const UPDATE_ITEM_POSITION_MUTATION = `
mutation UpdateItemPosition($projectId: ID!, $itemId: ID!, $afterId: ID) {
	updateProjectV2ItemPosition(input: {projectId: $projectId, itemId: $itemId, afterId: $afterId }) {
		clientMutationId
	}
}`;
