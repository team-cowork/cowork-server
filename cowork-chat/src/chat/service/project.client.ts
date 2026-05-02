import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

export interface GithubRepoInfo {
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
            const res = await fetch(`${this.projectServiceUrl}/projects/${projectId}`);
            if (!res.ok) {
                this.logger.warn(`프로젝트 조회 실패 projectId=${projectId} status=${res.status}`);
                return null;
            }
            const body = await res.json() as { githubRepoUrl?: string | null };
            return this.parseRepoUrl(body.githubRepoUrl);
        } catch (err) {
            this.logger.error(`프로젝트 서비스 호출 오류 projectId=${projectId}`, err);
            return null;
        }
    }

    private parseRepoUrl(url: string | null | undefined): GithubRepoInfo | null {
        if (!url) return null;
        // https://github.com/{owner}/{repo} 또는 https://github.com/{owner}/{repo}.git
        const match = url.match(/github\.com\/([^/]+)\/([^/]+?)(?:\.git)?(?:\/.*)?$/);
        if (!match) return null;
        return { owner: match[1], repo: match[2] };
    }
}
