import { Injectable } from '@nestjs/common';
import { ProjectGithubRepoProjectionRepository } from '../repository/project-github-repo-projection.repository';
import { ProjectMemberProjectionRepository } from '../repository/project-member-projection.repository';

/** GitHub 저장소 이벤트를 게시할 대상(팀/프로젝트/알림 채널) 정보. */
export interface GithubWebhookTarget {
    teamId: number;
    projectId: number;
    channelId: number;
}

/** Kafka로 동기화된 프로젝트 멤버십·GitHub 저장소 projection 조회기. */
@Injectable()
export class ProjectClient {
    constructor(
        private readonly memberRepository: ProjectMemberProjectionRepository,
        private readonly repoRepository: ProjectGithubRepoProjectionRepository,
    ) {}

    /** projection에 멤버십이 없으면 권한을 부여하지 않는다. */
    async isMember(projectId: number, userId: number): Promise<boolean> {
        return this.memberRepository.exists(projectId, userId);
    }

    /** 같은 GitHub 저장소를 연결한 모든 프로젝트의 활성 알림 대상을 반환한다. */
    async getGithubWebhookTargets(owner: string, repo: string): Promise<GithubWebhookTarget[]> {
        const projections = await this.repoRepository.findWebhookTargets(owner, repo);
        return projections.flatMap((projection) => projection.webhookChannelId === null
            ? []
            : [{
                teamId: projection.teamId,
                projectId: projection.projectId,
                channelId: projection.webhookChannelId,
            }]);
    }
}
