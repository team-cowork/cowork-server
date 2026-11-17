import {HttpService} from '@nestjs/axios';
import {Injectable as injectable} from '@nestjs/common';
import {AxiosError} from 'axios';
import {firstValueFrom} from 'rxjs';
import {GithubAuthService} from '../auth/github-auth.service.js';
import {GITHUB_API, GITHUB_HEADERS} from '../constants.js';
import {githubField} from '../github-field.js';
import {GithubClientError} from '../github.errors.js';

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
		const token = await this.authService.getInstallationToken(owner);
		let body: GraphqlResponse<T>;
		try {
			const response = await firstValueFrom(
				this.httpService.post<GraphqlResponse<T>>(
					`${GITHUB_API}/graphql`,
					{query, variables},
					{headers: {...GITHUB_HEADERS, [githubField.authorization]: `Bearer ${token}`}},
				),
			);
			body = response.data;
		} catch (error) {
			if (
				error instanceof AxiosError
				&& error.response
				&& error.response.status < 500
				&& error.response.status !== 429
			) {
				throw new GithubClientError(
					(error.response.data as {message?: string})?.message ?? error.message,
					error.response.status,
				);
			}

			throw error;
		}

		if (body.errors !== undefined && body.errors.length > 0) {
			// RATE_LIMITED 같은 일시적인 오류는 일반 오류로 던져 호출자가 재시도하게 한다.
			throw toGraphqlClientError(body.errors) ?? new Error(`GitHub GraphQL error: ${JSON.stringify(body.errors)}`);
		}

		if (body.data === undefined) {
			throw new Error('GitHub GraphQL response has no data');
		}

		return body.data;
	}
}
