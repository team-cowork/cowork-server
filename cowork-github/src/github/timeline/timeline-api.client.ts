import {HttpService} from '@nestjs/axios';
import {Injectable as injectable} from '@nestjs/common';
import {firstValueFrom} from 'rxjs';
import {GithubAuthService} from '../auth/github-auth.service.js';
import {GITHUB_API, githubAuthHeaders} from '../constants.js';
import {GithubClientError} from '../github.errors.js';
import {githubField} from '../github-field.js';
import {requestGithub} from '../github-rate-limit.js';
import {type TimelineEvent} from './timeline-activity.js';

export type TimelineParent = 'ISSUE' | 'PULL_REQUEST';

@injectable()
export class TimelineApiClient {
	constructor(private readonly http: HttpService, private readonly auth: GithubAuthService) {}

	private async get<T>(owner: string, path: string, page?: number) {
		return requestGithub(async () => {
			const token = await this.auth.getInstallationToken(owner);
			// 이전된 비공개 이슈나 저장소를 예전 프로젝트 권한으로 읽지 않는다.
			return firstValueFrom(this.http.get<T>(`${GITHUB_API}/repos/${path}`, {
				headers: githubAuthHeaders(token), maxRedirects: 0, timeout: 15_000, params: page === undefined ? undefined : {[githubField.perPage]: 100, page},
			}));
		});
	}

	private async * listPages(owner: string, path: string, isReviewCommentPage: boolean): AsyncGenerator<TimelineEvent[]> {
		for (let page = 1; ; page++) {
			// eslint-disable-next-line no-await-in-loop -- GitHub의 Link 헤더가 다음 페이지 유무를 결정한다.
			const {data, headers} = await this.get<TimelineEvent[]>(owner, path, page);
			yield isReviewCommentPage ? data.map(comment => ({...comment, event: 'review_commented'})) : data;
			if (typeof headers.link !== 'string' || !headers.link.includes('rel="next"')) {
				return;
			}
		}
	}

	async parent(owner: string, repo: string, number: number): Promise<TimelineParent> {
		if ([owner, repo].some(part => !/^[\w\-.]+$/v.test(part) || part === '.' || part === '..')) {
			throw new GithubClientError('저장소 이름이 올바르지 않습니다.', 400);
		}

		const {data} = await this.get<{pull_request?: unknown}>(owner, `${owner}/${repo}/issues/${number}`);
		return data.pull_request === undefined ? 'ISSUE' : 'PULL_REQUEST';
	}

	async * pages(owner: string, repo: string, number: number, parent: TimelineParent): AsyncGenerator<TimelineEvent[]> {
		yield * this.listPages(owner, `${owner}/${repo}/issues/${number}/timeline`, false);
		if (parent === 'PULL_REQUEST') {
			yield * this.listPages(owner, `${owner}/${repo}/pulls/${number}/comments`, true);
		}
	}
}
