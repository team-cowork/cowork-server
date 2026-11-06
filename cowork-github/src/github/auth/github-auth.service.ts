import {HttpService} from '@nestjs/axios';
import {Inject as inject, Injectable as injectable, Logger} from '@nestjs/common';
import {AxiosError} from 'axios';
import jsonwebtoken from 'jsonwebtoken';
import type {Redis} from 'ioredis';
import {firstValueFrom} from 'rxjs';
import {githubField} from '../github-field.js';
import {AppConfigService} from '../../config/app-config.service.js';
import {
	GITHUB_API,
	GITHUB_HEADERS,
	GITHUB_OAUTH_ACCESS_TOKEN_URL,
	GITHUB_USER_INSTALLATIONS_PAGE_SIZE,
	REDIS_CLIENT,
} from '../constants.js';
import {githubCacheKeys} from '../github.cache.js';
import {GithubClientError} from '../github.errors.js';

const EVICT_INSTALLATION_SCRIPT = `
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
`;

@injectable()
export class GithubAuthService {
	private readonly logger = new Logger(GithubAuthService.name);

	constructor(
		private readonly httpService: HttpService,
		private readonly config: AppConfigService,
		@inject(REDIS_CLIENT) private readonly redis: Redis,
	) {}

	private async resolveInstallationId(owner: string): Promise<number> {
		const installationCacheKey = githubCacheKeys.installation(owner);
		const cachedInRedis = await this.redis.get(installationCacheKey);
		if (cachedInRedis !== null && cachedInRedis !== '') {
			return Number(cachedInRedis);
		}

		const jwt = this.generateJwt();
		const {data} = await this.requestGithub<{id: number}>(async () =>
			firstValueFrom(
				this.httpService.get<{id: number}>(
					`${GITHUB_API}/orgs/${owner}/installation`,
					{
						headers: {...GITHUB_HEADERS, [githubField.authorization]: `Bearer ${jwt}`},
					},
				),
			),
		);
		const installationId = data.id;

		// NX: 조회 중에 설치 이벤트가 기록한 새 매핑을 이전 조회 결과로 덮지 않는다.
		await this.redis.set(
			installationCacheKey,
			installationId.toString(),
			'EX',
			this.config.githubInstallationCacheTtlSeconds,
			'NX',
		);

		return installationId;
	}

	private generateJwt(): string {
		const now = Math.floor(Date.now() / 1000);

		return jsonwebtoken.sign(
			{iat: now - 60, exp: now + 540, iss: this.config.githubAppId},
			this.config.githubPrivateKey,
			{algorithm: 'RS256'},
		);
	}

	private async requestGithub<T>(
		request: () => Promise<{data: T}>,
	): Promise<{data: T}> {
		try {
			return await request();
		} catch (error) {
			if (
				error instanceof AxiosError
				&& error.response
				&& error.response.status < 500
				&& error.response.status !== 429
			) {
				throw new GithubClientError(
					(error.response.data as {message?: string})?.message
					?? error.message,
					error.response.status,
				);
			}

			throw error;
		}
	}

	// GitHub는 잘못되거나 만료된 code에도 200으로 응답하고 본문에 error만 담으므로, 토큰이 없으면 undefined를 돌려준다.
	private async exchangeUserCode(code: string): Promise<string | undefined> {
		const {data} = await this.requestGithub<Record<string, string | undefined>>(async () =>
			firstValueFrom(
				this.httpService.post<Record<string, string | undefined>>(
					GITHUB_OAUTH_ACCESS_TOKEN_URL,
					{
						[githubField.clientId]: this.config.githubAppClientId,
						[githubField.clientSecret]: this.config.githubAppClientSecret,
						code,
					},
					{headers: {[githubField.accept]: 'application/json'}},
				),
			),
		);
		const accessToken = data[githubField.accessToken];
		if (accessToken === undefined || accessToken === '') {
			this.logger.warn(`User authorization code exchange failed [error=${data.error ?? 'unknown'}]`);
			return undefined;
		}

		return accessToken;
	}

	async getInstallationToken(owner: string): Promise<string> {
		const installationId = await this.resolveInstallationId(owner);
		const cacheKey = githubCacheKeys.token(installationId);

		const cached = await this.redis.get(cacheKey);
		if (cached !== null && cached !== '') {
			return cached;
		}

		const jwt = this.generateJwt();
		const {data} = await this.requestGithub<{token: string}>(async () =>
			firstValueFrom(
				this.httpService.post<{token: string}>(
					`${GITHUB_API}/app/installations/${installationId}/access_tokens`,
					{},
					{headers: {...GITHUB_HEADERS, [githubField.authorization]: `Bearer ${jwt}`}},
				),
			),
		);

		await this.redis.set(
			cacheKey,
			data.token,
			'EX',
			this.config.githubTokenCacheTtlSeconds,
		);
		this.logger.log(
			`Installation token issued for installationId=${installationId}`,
		);
		return data.token;
	}

	async getInstallationAccountLogin(installationId: number): Promise<string> {
		const jwt = this.generateJwt();
		const {data} = await this.requestGithub<{account: {login: string}}>(
			async () =>
				firstValueFrom(
					this.httpService.get<{account: {login: string}}>(
						`${GITHUB_API}/app/installations/${installationId}`,
						{headers: {...GITHUB_HEADERS, [githubField.authorization]: `Bearer ${jwt}`}},
					),
				),
		);
		return data.account.login;
	}

	// 설치 중 사용자 인증으로 받은 code를 사용자 토큰으로 바꾸고, 그 사용자가 접근할 수 있는 설치에 installationId가 있는지 확인한다.
	async canUserAccessInstallation(code: string, installationId: number): Promise<boolean> {
		const userToken = await this.exchangeUserCode(code);
		if (userToken === undefined) {
			return false;
		}

		const headers = {...GITHUB_HEADERS, [githubField.authorization]: `Bearer ${userToken}`};
		for (let page = 1; ; page++) {
			// eslint-disable-next-line no-await-in-loop -- 다음 페이지가 필요한지는 이전 응답으로만 알 수 있다.
			const {data} = await this.requestGithub<{installations: Array<{id: number}>}>(async () =>
				firstValueFrom(
					this.httpService.get<{installations: Array<{id: number}>}>(
						`${GITHUB_API}/user/installations`,
						{params: {[githubField.perPage]: GITHUB_USER_INSTALLATIONS_PAGE_SIZE, page}, headers},
					),
				),
			);
			if (data.installations.some(installation => installation.id === installationId)) {
				return true;
			}

			if (data.installations.length < GITHUB_USER_INSTALLATIONS_PAGE_SIZE) {
				return false;
			}
		}
	}

	// 설치 이벤트가 알려준 매핑은 조회 결과보다 우선하므로 그대로 덮어쓴다.
	async cacheInstallation(owner: string, installationId: number): Promise<void> {
		await this.redis.set(
			githubCacheKeys.installation(owner),
			installationId.toString(),
			'EX',
			this.config.githubInstallationCacheTtlSeconds,
		);
	}

	// 늦게 도착한 이전 설치의 삭제가 재설치한 매핑을 지우지 않도록, 같은 설치 ID일 때만 지운다.
	async evictInstallation(owner: string, installationId: number): Promise<void> {
		await this.redis.eval(
			EVICT_INSTALLATION_SCRIPT,
			1,
			githubCacheKeys.installation(owner),
			installationId.toString(),
		);
		await this.redis.del(githubCacheKeys.token(installationId));
	}
}
