import {HttpService} from '@nestjs/axios';
import {Injectable as injectable} from '@nestjs/common';
import {firstValueFrom} from 'rxjs';
import {GithubAuthService} from '../auth/github-auth.service.js';
import {GITHUB_API, githubAuthHeaders} from '../constants.js';
import {requestGithub} from '../github-rate-limit.js';
import {GithubClientError, GithubRateLimitError} from '../github.errors.js';

type GraphqlError = {type?: string; message?: string};

type GraphqlResponse<T> = {data?: T; errors?: GraphqlError[]};

// GitHub는 권한 문제와 대상 문제도 HTTP 200과 errors 배열로 돌려준다. 재시도해도 결과가 같은 오류만 클라이언트 오류로 바꾼다.
const CLIENT_ERROR_STATUS = new Map<string, number>([
	['NOT_FOUND', 404],
	['FORBIDDEN', 403],
	['INSUFFICIENT_SCOPES', 403],
	['UNPROCESSABLE', 422],
]);

const RESOURCE_NOT_ACCESSIBLE = 'Resource not accessible by integration';

export const toGraphqlClientError = (errors: GraphqlError[]): GithubClientError | undefined => {
	const message = errors.map(error => error.message ?? 'GitHub GraphQL error').join('; ');
	for (const error of errors) {
		const status = error.type === undefined ? undefined : CLIENT_ERROR_STATUS.get(error.type);
		if (status !== undefined) {
			return new GithubClientError(message, status);
		}

		if (error.message?.includes(RESOURCE_NOT_ACCESSIBLE) === true) {
			return new GithubClientError(message, 403);
		}
	}

	return undefined;
};

// GitHub는 GraphQL 기본 호출 한도도 HTTP 200과 RATE_LIMITED 오류로 돌려주며 대기 시간을 알려 주지 않는다.
export const toGraphqlRateLimitError = (errors: GraphqlError[] | undefined): GithubRateLimitError | undefined => {
	const limited = errors?.find(error => error.type === 'RATE_LIMITED');
	return limited === undefined ? undefined : new GithubRateLimitError(limited.message ?? 'GitHub GraphQL rate limit exceeded');
};

/**
 GitHub GraphQL API를 설치 토큰으로 호출한다. Projects(v2)는 REST로 view 설정을 읽을 수 없어 GraphQL을 쓴다.
 */
@injectable()
export class GithubGraphqlClient {
	constructor(
		private readonly httpService: HttpService,
		private readonly authService: GithubAuthService,
	) {}

	async request<T>(owner: string, query: string, variables: Record<string, unknown>): Promise<T> {
		const {data: body} = await requestGithub(async () => {
			const token = await this.authService.getInstallationToken(owner);
			return firstValueFrom(
				this.httpService.post<GraphqlResponse<T>>(
					`${GITHUB_API}/graphql`,
					{query, variables},
					{headers: githubAuthHeaders(token)},
				),
			);
		}, ({data}) => toGraphqlRateLimitError(data.errors));

		if (body.errors !== undefined && body.errors.length > 0) {
			// Kafka 명령 밖에서 받은 RATE_LIMITED 같은 일시적인 오류는 일반 오류로 던져 호출자가 재시도하게 한다.
			throw toGraphqlClientError(body.errors) ?? new Error(`GitHub GraphQL error: ${JSON.stringify(body.errors)}`);
		}

		if (body.data === undefined) {
			throw new Error('GitHub GraphQL response has no data');
		}

		return body.data;
	}
}
