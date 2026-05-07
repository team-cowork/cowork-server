import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { getRequiredConfig } from '../../common/config/config.util';

export interface GithubRepoInfo {
    teamId: number;
    owner: string;
    repo: string;
}

@Injectable()
export class ProjectClient {
    private readonly logger = new Logger(ProjectClient.name);
    private readonly projectServiceUrl: string;

    constructor(private readonly configService: ConfigService) {
        this.projectServiceUrl = getRequiredConfig(this.configService, 'PROJECT_SERVICE_URL').replace(/\/$/, '');
    }

    async getGithubRepoInfo(projectId: number): Promise<GithubRepoInfo | null> {
        try {
            const res = await fetch(`${this.projectServiceUrl}/projects/${projectId}`, {
                signal: AbortSignal.timeout(5000),
            });
            if (res.status === 404) return null;
            if (!res.ok) {
                throw new Error(`프로젝트 서비스 응답 오류: ${res.status}`);
            }
            const body = await res.json() as { teamId?: number | null; githubRepoUrl?: string | null };
            const repoInfo = this.parseRepoUrl(body.githubRepoUrl);
            if (!repoInfo || typeof body.teamId !== 'number') return null;
            return { teamId: body.teamId, ...repoInfo };
        } catch (err) {
            this.logger.error(`프로젝트 서비스 호출 오류 projectId=${projectId}`, err);
            throw err;
        }
    }

    private parseRepoUrl(url: string | null | undefined): Omit<GithubRepoInfo, 'teamId'> | null {
        if (!url) return null;
        try {
            const parsed = new URL(url);
            if (parsed.hostname !== 'github.com') return null;
            const parts = parsed.pathname.split('/').filter(Boolean);
            if (parts.length < 2) return null;
            return { owner: parts[0], repo: parts[1].replace(/\.git$/, '') };
        } catch {
            return null;
        }
    }
}
