import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

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
        this.projectServiceUrl = this.configService.get<string>('PROJECT_SERVICE_URL', 'http://localhost:8084');
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
        // https://github.com/{owner}/{repo} 또는 https://github.com/{owner}/{repo}.git
        const match = url.match(/github\.com\/([^/]+)\/([^/]+?)(?:\.git)?(?:\/.*)?$/);
        if (!match) return null;
        return { owner: match[1], repo: match[2] };
    }
}
