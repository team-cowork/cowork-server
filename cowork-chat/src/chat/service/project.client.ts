import { ConflictException, Injectable } from '@nestjs/common';
import { ProjectGithubRepoProjectionRepository } from '../repository/project-github-repo-projection.repository';
import { ProjectMemberProjectionRepository } from '../repository/project-member-projection.repository';

/** GitHub 저장소 연동 정보. */
export interface GithubRepoInfo {
    repoId: number;
    teamId: number;
    owner: string;
    repo: string;
}

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

    /** 현재 request 계약에 repoId가 없으므로 활성 연결이 정확히 하나일 때만 선택한다. */
    async getGithubRepoInfo(projectId: number): Promise<GithubRepoInfo | null> {
        const projections = await this.repoRepository.findAllByProjectId(projectId);
        if (projections.length === 0) return null;
        if (projections.length > 1) {
            throw new ConflictException('프로젝트에 연결된 저장소가 여러 개여서 이슈 대상을 결정할 수 없습니다');
        }
        return this.toRepoInfo(projections[0]);
    }

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

    private toRepoInfo(projection: {
        repoId: number;
        teamId: number;
        owner: string;
        repo: string;
    }): GithubRepoInfo {
        return {
            repoId: projection.repoId,
            teamId: projection.teamId,
            owner: projection.owner,
            repo: projection.repo,
        };
    }
}
