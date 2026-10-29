import {Injectable as injectable, ServiceUnavailableException, Inject as inject} from '@nestjs/common';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {
	ProjectGithubRepoProjectionRepository,
	ProjectPermissionProjectionRepository,
	ProjectProjectionRepository,
	TeamMemberProjectionRepository,
} from '../repository/index.js';

/**
GitHub 저장소 이벤트를 게시할 대상(팀/프로젝트/알림 채널) 정보.
*/
export type GithubWebhookTarget = {
	teamId: number;
	projectId: number;
	channelId: number;
};

/**
Kafka로 동기화된 프로젝트 권한·GitHub 저장소 projection 조회기.
*/
@injectable()
export class ProjectClient {
	@inject()
	private readonly permissionRepository!: ProjectPermissionProjectionRepository;

	@inject()
	private readonly repoRepository!: ProjectGithubRepoProjectionRepository;

	@inject()
	private readonly projectRepository!: ProjectProjectionRepository;

	@inject()
	private readonly teamMemberRepository!: TeamMemberProjectionRepository;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	async canRead(projectId: number, userId: number): Promise<boolean> {
		if (!this.projectionReadiness.isReady()) {
			throw new ServiceUnavailableException('권한 정보가 아직 동기화되지 않았습니다');
		}

		const project = await this.projectRepository.findById(projectId);
		if (!project) {
			return false;
		}

		const members = await this.teamMemberRepository.findByTeamIdsAndUserIds([project.teamId], [userId]);
		const member = members.at(0);
		if (!member) {
			return false;
		}

		if (member.role === 'OWNER' || member.role === 'ADMIN') {
			return true;
		}

		// 호환 정리(#596): 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 nullable fallback 제거.
		// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
		const authorizationVersion = member.authorizationVersion ?? member.sourceVersion;
		return authorizationVersion === undefined ? false : this.permissionRepository.canRead(projectId, userId, authorizationVersion);
	}

	/**
    같은 GitHub 저장소를 연결한 모든 프로젝트의 활성 알림 대상을 반환한다.
    */
	async getGithubWebhookTargets(owner: string, repo: string): Promise<GithubWebhookTarget[]> {
		const projections = await this.repoRepository.findWebhookTargets(owner, repo);
		return projections.flatMap(projection => projection.webhookChannelId === null
			? []
			: [{
				teamId: projection.teamId,
				projectId: projection.projectId,
				channelId: projection.webhookChannelId,
			}]);
	}
}
