import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { getRequiredConfig } from '../../common/config/config.util';
import { BaseHttpClient } from './base-http-client';
import { ProjectMemberCache } from './project-member.cache';
import { ProjectRepoCache } from './project-repo.cache';

/**
 * GitHub 저장소 연동 정보.
 */
export interface GithubRepoInfo {
    teamId: number;
    owner: string;
    repo: string;
}

/**
 * GitHub 저장소 이벤트를 게시할 대상(팀/프로젝트/알림 채널) 정보.
 */
export interface GithubWebhookTarget {
    teamId: number;
    projectId: number;
    channelId: number;
}

/**
 * project-service와 HTTP 통신하는 클라이언트.
 *
 * 환경변수 `PROJECT_SERVICE_URL`을 베이스 URL로 사용하며,
 * 말미 슬래시는 자동으로 제거된다.
 */
@Injectable()
export class ProjectClient extends BaseHttpClient {
    protected readonly logger = new Logger(ProjectClient.name);
    protected readonly serviceName = 'project-service';
    private readonly projectServiceUrl: string;

    constructor(
        private readonly configService: ConfigService,
        private readonly memberCache: ProjectMemberCache,
        private readonly repoCache: ProjectRepoCache,
    ) {
        super();
        this.projectServiceUrl = getRequiredConfig(this.configService, 'PROJECT_SERVICE_URL').replace(/\/$/, '');
    }

    /**
     * 프로젝트에 연결된 GitHub 저장소 정보를 조회한다.
     *
     * - 프로젝트가 존재하지 않으면 (404) `null`을 반환한다.
     * - 응답에 `githubRepoUrl`이 없거나 파싱에 실패하면 `null`을 반환한다.
     * - `teamId`가 숫자가 아닌 경우에도 `null`을 반환한다.
     * - 위 조건 외의 네트워크/HTTP 오류는 예외로 전파한다.
     *
     * @param projectId - 조회할 프로젝트의 ID
     * @returns GitHub 저장소 정보, 존재하지 않거나 파싱 불가 시 `null`
     * @throws {Error} 404 이외의 HTTP 오류 또는 네트워크 오류 발생 시
     *
     * 조회 결과(저장소 정보 없음 포함)는 Redis에 30초 TTL로 캐싱된다.
     * Redis 조회 실패 시에는 캐시 미스로 간주해 항상 project-service로 폴백한다.
     */
    async getGithubRepoInfo(projectId: number): Promise<GithubRepoInfo | null> {
        const cached = await this.repoCache.get(projectId);
        if (cached !== undefined) return cached;

        try {
            const res = await this.fetchWithRetry(`${this.projectServiceUrl}/projects/${projectId}`, {}, 5000);
            if (res.status === 404) {
                await this.repoCache.set(projectId, null);
                return null;
            }
            if (!res.ok) {
                throw new Error(`프로젝트 서비스 응답 오류: ${res.status}`);
            }
            const body = await this.readJsonBody<{ teamId?: number | null; githubRepoUrl?: string | null }>(res);
            const repoInfo = this.parseRepoUrl(body.githubRepoUrl);
            const result = (!repoInfo || typeof body.teamId !== 'number') ? null : { teamId: body.teamId, ...repoInfo };
            await this.repoCache.set(projectId, result);
            return result;
        } catch (err) {
            this.logger.error(`Failed to call project-service projectId=${projectId}`, err);
            throw err;
        }
    }

    /**
     * 특정 사용자가 프로젝트의 멤버인지 확인한다.
     *
     * 요청 시 `X-User-Id` 헤더로 사용자 ID를 전달한다.
     * project-service가 404를 반환하면 멤버가 아닌 것으로 간주하여 `false`를 반환한다.
     * 그 외 HTTP 오류 및 네트워크 오류는 예외로 전파한다.
     *
     * 동일 `(projectId, userId)` 조합의 결과는 Redis에 30초 TTL로 캐싱된다.
     * Redis 조회 실패 시에는 캐시 미스로 간주해 항상 project-service로 폴백한다.
     *
     * @param projectId - 확인할 프로젝트의 ID
     * @param userId - 확인할 사용자의 ID (`X-User-Id` 헤더로 전달됨)
     * @returns 멤버이면 `true`, 404이면 `false`
     * @throws {Error} 404 이외의 HTTP 오류 또는 네트워크 오류 발생 시
     */
    async isMember(projectId: number, userId: number): Promise<boolean> {
        const cached = await this.memberCache.get(projectId, userId);
        if (cached !== null) return cached;

        try {
            const res = await this.fetchWithRetry(
                `${this.projectServiceUrl}/projects/${projectId}/members/me`,
                { headers: { 'X-User-Id': String(userId) } },
                3000,
            );
            try {
                if (res.status === 404) {
                    await this.memberCache.set(projectId, userId, false);
                    return false;
                }
                if (!res.ok) throw new Error(`project-service 오류: ${res.status}`);
                await this.memberCache.set(projectId, userId, true);
                return true;
            } finally {
                void res.body?.cancel().catch((err: unknown) => {
                    this.logger.warn(`Failed to cancel response body projectId=${projectId} userId=${userId}`, err);
                });
            }
        } catch (err) {
            this.logger.error(`Failed to check membership in project-service projectId=${projectId} userId=${userId}`, err);
            throw err;
        }
    }

    /**
     * `owner`/`repo`에 연결된 프로젝트의 GitHub 알림 대상 채널 정보를 조회한다.
     *
     * - 연결된 프로젝트가 없거나 알림 채널이 지정되지 않은 경우 (404) `null`을 반환한다.
     * - 위 조건 외의 네트워크/HTTP 오류는 예외로 전파한다.
     *
     * 이벤트 발생 빈도가 낮아 별도 캐싱 없이 매번 실시간 조회한다.
     *
     * @param owner - GitHub 저장소 소유자
     * @param repo - GitHub 저장소 이름
     * @returns GitHub 알림 대상 정보, 연결된 프로젝트/채널이 없으면 `null`
     * @throws {Error} 404 이외의 HTTP 오류 또는 네트워크 오류 발생 시
     */
    /**
     * 서로 다른 팀이 같은 레포를 연결할 수 있어 0개 이상의 대상을 반환할 수 있다.
     */
    async getGithubWebhookTargets(owner: string, repo: string): Promise<GithubWebhookTarget[]> {
        try {
            const res = await this.fetchWithRetry(
                `${this.projectServiceUrl}/projects/github-webhook-target?owner=${encodeURIComponent(owner)}&repo=${encodeURIComponent(repo)}`,
                {},
                5000,
            );
            if (!res.ok) throw new Error(`프로젝트 서비스 응답 오류: ${res.status}`);
            return await this.readJsonBody<GithubWebhookTarget[]>(res);
        } catch (err) {
            this.logger.error(`Failed to resolve github webhook targets owner=${owner} repo=${repo}`, err);
            throw err;
        }
    }

    /**
     * GitHub 저장소 URL을 파싱하여 owner와 repo를 추출한다.
     *
     * `github.com` 도메인만 허용하며, 다른 도메인이면 `null`을 반환한다.
     * repo 이름 말미의 `.git` suffix는 자동으로 제거된다.
     * URL 파싱 자체가 실패(malformed URL 등)해도 예외를 던지지 않고 `null`을 반환한다.
     *
     * @param url - 파싱할 GitHub 저장소 URL (예: `https://github.com/owner/repo.git`)
     * @returns owner와 repo가 포함된 객체, 파싱 불가 시 `null`
     */
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
