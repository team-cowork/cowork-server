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
import {
	ApiCreatedResponse as apiCreatedResponse,
	ApiOkResponse as apiOkResponse,
	ApiOperation as apiOperation,
	ApiParam as apiParameter,
	ApiResponse as apiResponse,
	ApiSecurity as apiSecurity,
	ApiTags as apiTags,
} from '@nestjs/swagger';
import {internalApiErrorSchema} from '../../config/swagger-schemas.js';
import {GithubClientError} from '../github.errors.js';
import {InternalApiKeyGuard} from '../common/guards/internal-api-key.guard.js';
import {PullRequestActionRequestDto} from './dto/pull-request-action-request.dto.js';
import {PullRequestService} from './pull-request.service.js';

@apiTags('pull-request')
@apiSecurity('internal-api-key')
@apiParameter({
	name: 'owner', description: '저장소 소유자 또는 조직', type: String, example: 'octo-org',
})
@apiParameter({
	name: 'repo', description: '저장소 이름', type: String, example: 'example',
})
@apiParameter({
	name: 'number', description: 'PR 번호', schema: {type: 'integer', example: 42},
})
@apiResponse({status: 400, description: 'PR 번호/JSON 본문/요청 필드 형식 오류', schema: internalApiErrorSchema})
@apiResponse({status: 401, description: '내부 API 키 누락/불일치 또는 GitHub 인증 실패', schema: internalApiErrorSchema})
@apiResponse({status: 403, description: 'GitHub 접근 거부/요청 한도 초과', schema: internalApiErrorSchema})
@apiResponse({status: 404, description: 'GitHub App 설치/저장소/PR 조회 대상 없음', schema: internalApiErrorSchema})
@apiResponse({status: 422, description: 'GitHub 요청 검증 실패/반복 요청 거부', schema: internalApiErrorSchema})
@apiResponse({status: 502, description: 'GitHub 429/5xx 응답 또는 내부 처리/통신 오류', schema: internalApiErrorSchema})
@apiResponse({status: 'default', description: '기타 요청/서버 오류', schema: internalApiErrorSchema})
@controller('api/repos/:owner/:repo/pulls/:number')
@useGuards(InternalApiKeyGuard)
export class PullRequestHttpController {
	constructor(private readonly pullRequestService: PullRequestService) {}

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

	@httpGet('live-status')
	@apiOperation({summary: 'PR 실시간 상태 조회', description: 'GitHub에서 현재 머지 가능 여부와 리뷰 결과를 조회합니다.'})
	@apiOkResponse({
		description: 'PR 실시간 상태',
		schema: {
			type: 'object',
			required: ['mergeable', 'mergeableState', 'reviewDecision'],
			properties: {
				mergeable: {type: 'boolean', nullable: true, description: '머지 가능 여부 (계산 중이면 null)'},
				mergeableState: {type: 'string', example: 'clean'},
				reviewDecision: {type: 'string', nullable: true, enum: ['APPROVED', 'CHANGES_REQUESTED', null]},
			},
		},
	})
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
	@apiOperation({summary: 'PR 머지', description: '이미 머지된 PR은 alreadyMerged=true를 반환합니다. 그 외에는 요청자의 쓰기 권한을 확인하고 머지합니다. 머지 후 같은 저장소의 원본 브랜치 삭제를 시도합니다.'})
	@apiCreatedResponse({
		description: 'PR 머지 결과',
		schema: {
			type: 'object',
			required: ['alreadyMerged', 'prUrl', 'prNumber'],
			properties: {
				alreadyMerged: {type: 'boolean'},
				prUrl: {type: 'string', format: 'uri'},
				prNumber: {type: 'integer', example: 42},
			},
		},
	})
	@apiResponse({status: 403, description: '요청자의 쓰기 권한 부족 또는 GitHub 접근 거부/요청 한도 초과', schema: internalApiErrorSchema})
	@apiResponse({status: 409, description: '머지 불가/충돌 (GitHub 405 응답 포함)', schema: internalApiErrorSchema})
	@apiResponse({status: 413, description: '요청 본문 크기 제한 초과', schema: internalApiErrorSchema})
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
	@apiOperation({summary: 'PR 승인', description: '요청자의 저장소 쓰기 권한을 확인합니다. 자신이 작성한 PR은 승인할 수 없습니다.'})
	@apiCreatedResponse({
		description: 'PR 승인 결과',
		schema: {
			type: 'object',
			required: ['prUrl', 'prNumber'],
			properties: {
				prUrl: {type: 'string', format: 'uri'},
				prNumber: {type: 'integer', example: 42},
			},
		},
	})
	@apiResponse({status: 403, description: '본인 PR/쓰기 권한 부족 또는 GitHub 접근 거부/요청 한도 초과', schema: internalApiErrorSchema})
	@apiResponse({status: 413, description: '요청 본문 크기 제한 초과', schema: internalApiErrorSchema})
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
}
