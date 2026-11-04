/**
GitHub REST API와 HTTP 헤더가 정한 필드 이름이다. 외부 계약이므로 camelCase로 바꾸지 않는다.
*/
export const githubField = {
	accept: 'Accept',
	authorization: 'Authorization',
	createdAt: 'created_at',
	fullName: 'full_name',
	htmlUrl: 'html_url',
	mergeableState: 'mergeable_state',
	mergedAt: 'merged_at',
	mergeMethod: 'merge_method',
	perPage: 'per_page',
	pullRequest: 'pull_request',
	repositoriesAdded: 'repositories_added',
	repositoriesRemoved: 'repositories_removed',
	submittedAt: 'submitted_at',
	totalCount: 'total_count',
	updatedAt: 'updated_at',
} as const;
