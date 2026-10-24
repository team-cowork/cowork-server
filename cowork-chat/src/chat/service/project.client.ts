import {Injectable, ServiceUnavailableException} from '@nestjs/common';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service';
import {
    ProjectGithubRepoProjectionRepository,
    ProjectPermissionProjectionRepository,
    ProjectProjectionRepository,
    TeamMemberProjectionRepository,
} from '../repository';

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
@Injectable()
export class ProjectClient {
    constructor(
        private readonly permissionRepository: ProjectPermissionProjectionRepository,
        private readonly repoRepository: ProjectGithubRepoProjectionRepository,
        private readonly projectRepository: ProjectProjectionRepository,
        private readonly teamMemberRepository: TeamMemberProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

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

        return project.createdBy === userId || member.role === 'OWNER' || member.role === 'ADMIN'
            || this.permissionRepository.canRead(projectId, userId);
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
