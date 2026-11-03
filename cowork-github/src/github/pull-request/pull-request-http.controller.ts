import {
	Body as requestBody,
	Controller as controller,
	Get as httpGet,
	HttpException,
	Param as requestParameter,
	ParseIntPipe,
	Post as httpPost,
	UseGuards as useGuards,
} from '@nestjs/common';
import {GithubClientError} from '../github.errors.js';
import {InternalApiKeyGuard} from '../common/guards/internal-api-key.guard.js';
import {PullRequestActionRequestDto} from './dto/pull-request-action-request.dto.js';
import {PullRequestService} from './pull-request.service.js';

@controller('api/repos/:owner/:repo/pulls/:number')
@useGuards(InternalApiKeyGuard)
export class PullRequestHttpController {
	constructor(private readonly pullRequestService: PullRequestService) {}

	@httpGet('live-status')
	async getLiveStatus(
		@requestParameter('owner') owner: string,
		@requestParameter('repo') repo: string,
		@requestParameter('number', ParseIntPipe) prNumber: number,
	) {
		return this.handle(async () =>
			this.pullRequestService.getPullRequestLiveStatus(owner, repo, prNumber),
		);
	}

	@httpPost('merge')
	async merge(
		@requestParameter('owner') owner: string,
		@requestParameter('repo') repo: string,
		@requestParameter('number', ParseIntPipe) prNumber: number,
		@requestBody() body: PullRequestActionRequestDto,
	) {
		return this.handle(async () =>
			this.pullRequestService.mergePullRequest({
				owner,
				repo,
				prNumber,
				requesterGithubUsername: body.requesterGithubUsername,
			}),
		);
	}

	@httpPost('approve')
	async approve(
		@requestParameter('owner') owner: string,
		@requestParameter('repo') repo: string,
		@requestParameter('number', ParseIntPipe) prNumber: number,
		@requestBody() body: PullRequestActionRequestDto,
	) {
		return this.handle(async () =>
			this.pullRequestService.approvePullRequest({
				owner,
				repo,
				prNumber,
				requesterGithubUsername: body.requesterGithubUsername,
			}),
		);
	}

	private async handle<T>(fn: () => Promise<T>): Promise<T> {
		try {
			return await fn();
		} catch (error) {
			if (error instanceof GithubClientError) {
				throw new HttpException(error.message, error.statusCode);
			}

			throw new HttpException(
				'GitHub 서버와 통신 중 오류가 발생했습니다.',
				502,
			);
		}
	}
}
