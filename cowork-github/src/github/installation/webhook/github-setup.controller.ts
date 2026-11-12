import {
	Controller as controller,
	Get as httpGet,
	Header as responseHeader,
	Inject as inject,
	Logger,
	Query as requestQuery,
} from '@nestjs/common';
import {
	ApiOkResponse as apiOkResponse,
	ApiOperation as apiOperation,
	ApiQuery as apiQuery,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
} from '@nestjs/swagger';
import type {Redis} from 'ioredis';
import {gatewayApiErrorSchema} from '../../../config/swagger-schemas.js';
import {GithubAuthService} from '../../auth/github-auth.service.js';
import {REDIS_CLIENT} from '../../constants.js';
import {githubCacheKeys} from '../../github.cache.js';
import {GithubBackfillService} from '../github-backfill.service.js';
import {TeamGithubProducer} from '../kafka/team-github.producer.js';

@apiTags('installation')
@controller('github/setup')
export class GithubSetupController {
	private readonly logger = new Logger(GithubSetupController.name);

	constructor(
		private readonly authService: GithubAuthService,
		private readonly producer: TeamGithubProducer,
		private readonly backfillService: GithubBackfillService,
		@inject(REDIS_CLIENT) private readonly redis: Redis,
	) {}

	private page(message: string): string {
		return `<html><body style="font-family:sans-serif;text-align:center;padding-top:4rem;"><p>${message}</p></body></html>`;
	}

	@httpGet()
	@responseHeader('Content-Type', 'text/html; charset=utf-8')
	@apiOperation({
		summary: 'GitHub App 설치 콜백',
		description: '팀 연결에는 state와 code가 필요합니다. code로 사용자의 설치 접근 권한을 확인합니다.',
	})
	@apiQuery({
		name: 'installation_id', description: 'GitHub App 설치 ID', type: String, example: '12345',
	})
	@apiQuery({
		name: 'setup_action', description: '설치 작업 (delete는 연결 해제 안내)', type: String, example: 'install',
	})
	@apiQuery({
		name: 'state', description: 'cowork에서 발급한 팀 연결 상태 값', type: String, required: false,
	})
	@apiQuery({
		name: 'code', description: '설치를 진행한 사용자의 GitHub OAuth 코드', type: String, required: false,
	})
	@apiOkResponse({
		description: '연결 성공/실패/해제 안내 HTML (처리 실패도 HTTP 200)',
		content: {'text/html': {schema: {type: 'string'}}},
	})
	@apiResponse({status: 429, description: '설치 콜백 요청 한도 초과', schema: gatewayApiErrorSchema})
	@apiResponse({status: 'default', description: '기타 요청/서버 오류', schema: gatewayApiErrorSchema})
	async handle(
		@requestQuery('installation_id') installationIdRaw: string,
		@requestQuery('setup_action') setupAction: string,
		@requestQuery('state') state?: string,
		@requestQuery('code') code?: string,
	): Promise<string> {
		const installationId = Number(installationIdRaw);
		if (installationIdRaw === '' || !Number.isSafeInteger(installationId)) {
			return this.page('설치 정보를 확인할 수 없습니다. 다시 시도해주세요.');
		}

		if (setupAction === 'delete') {
			return this.page('GitHub 앱 연동이 해제되었습니다. 이 창을 닫아주세요.');
		}

		try {
			const orgLogin =
				await this.authService.getInstallationAccountLogin(installationId);
			// 팀 연결 여부와 무관하게, 백필이 이전 설치로 토큰을 받지 않도록 먼저 반영한다.
			await this.authService.cacheInstallation(orgLogin, installationId);
			if (state === undefined || state === '') {
				this.logger.warn(
					`Setup callback missing state, cannot correlate to a team [installationId=${installationId}]`,
				);
				return this.page(
					'GitHub App 설치는 완료되었지만 cowork 팀과 연결하지 못했습니다. cowork에서 다시 연동을 시도해주세요.',
				);
			}

			// 이 콜백은 인증 없이 열려 있으므로, 설치를 진행한 GitHub 사용자가 이 설치에 접근할 수 있을 때만 팀에 연결한다.
			if (
				code === undefined
				|| code === ''
				|| !(await this.authService.canUserAccessInstallation(code, installationId))
			) {
				this.logger.warn(
					`Setup callback user cannot be verified for installation, skipping team connection [installationId=${installationId}]`,
				);
				return this.page(
					'GitHub 설치 권한을 확인하지 못해 cowork 팀과 연결하지 않았습니다. cowork에서 다시 연동을 시도해주세요.',
				);
			}

			const revision = await this.redis.incr(
				githubCacheKeys.installationRevision(installationId),
			);

			await this.producer.send('team.github.connected', {
				state,
				installationId,
				orgLogin,
				revision,
			});
			// Repo 목록 projection을 바로 채운다. 이슈/PR 등은 cowork-project가 프로젝트에 연결된 repo만
			// github.sync.command.v1로 요청한다. 대기열에 넣기만 하므로 콜백 응답이 늦어지지 않는다.
			void this.backfillService.enqueue({type: 'ORG_REPOSITORIES', installationId, owner: orgLogin});
			return this.page(
				'GitHub 연동이 완료되었습니다. 이 창을 닫고 cowork로 돌아가세요.',
			);
		} catch (error) {
			this.logger.error(
				`Failed to process setup callback [installationId=${installationId}]`,
				error instanceof Error ? error.stack : String(error),
			);
			return this.page(
				'GitHub 연동 처리 중 오류가 발생했습니다. cowork에서 다시 시도해주세요.',
			);
		}
	}
}
