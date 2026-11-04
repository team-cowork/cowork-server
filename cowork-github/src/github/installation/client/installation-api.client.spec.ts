import {Test, type TestingModule} from '@nestjs/testing';
import {HttpService} from '@nestjs/axios';
import {AxiosError, type AxiosResponse} from 'axios';
import {of, throwError} from 'rxjs';
import {githubField} from '../../github-field.js';
import {GithubAuthService} from '../../auth/github-auth.service.js';
import {GithubClientError} from '../../github.errors.js';
import {InstallationApiClient} from './installation-api.client.js';

describe('InstallationApiClient', () => {
	let client: InstallationApiClient;
	let httpService: {get: jest.Mock};
	let authService: {getInstallationToken: jest.Mock};

	beforeEach(async () => {
		httpService = {get: jest.fn()};
		authService = {
			getInstallationToken: jest.fn().mockResolvedValue('my-token'),
		};

		const module: TestingModule = await Test.createTestingModule({
			providers: [
				InstallationApiClient,
				{provide: HttpService, useValue: httpService},
				{provide: GithubAuthService, useValue: authService},
			],
		}).compile();

		client = module.get<InstallationApiClient>(InstallationApiClient);
	});

	it('설치된 저장소 목록을 camelCase 형태로 매핑한다', async () => {
		httpService.get.mockReturnValue(
			of({
				data: {
					[githubField.totalCount]: 2,
					repositories: [
						{
							name: 'repo-a',
							[githubField.fullName]: 'my-org/repo-a',
							private: true,
							[githubField.htmlUrl]: 'https://github.com/my-org/repo-a',
						},
						{
							name: 'repo-b',
							[githubField.fullName]: 'my-org/repo-b',
							private: false,
							[githubField.htmlUrl]: 'https://github.com/my-org/repo-b',
						},
					],
				},
			}),
		);

		const result = await client.listRepositories('my-org');

		expect(result).toEqual([
			{
				name: 'repo-a',
				fullName: 'my-org/repo-a',
				private: true,
				htmlUrl: 'https://github.com/my-org/repo-a',
			},
			{
				name: 'repo-b',
				fullName: 'my-org/repo-b',
				private: false,
				htmlUrl: 'https://github.com/my-org/repo-b',
			},
		]);
	});

	it('installation 토큰을 사용해 per_page=100으로 요청한다', async () => {
		httpService.get.mockReturnValue(
			of({data: {[githubField.totalCount]: 0, repositories: []}}),
		);

		await client.listRepositories('my-org');

		expect(authService.getInstallationToken).toHaveBeenCalledWith('my-org');
		expect(httpService.get).toHaveBeenCalledWith(
			'https://api.github.com/installation/repositories',
			expect.objectContaining({
				params: {[githubField.perPage]: 100},
				headers: expect.objectContaining({
					[githubField.authorization]: 'Bearer my-token',
				}) as unknown,
			}),
		);
	});

	it('4xx 응답은 GithubClientError로 변환한다', async () => {
		const axiosError = new AxiosError('Not Found');
		axiosError.response = {
			data: {message: 'Not Found'},
			status: 404,
		} as unknown as AxiosResponse;
		httpService.get.mockReturnValue(throwError(() => axiosError));

		const promise = client.listRepositories('my-org');

		await expect(promise).rejects.toBeInstanceOf(GithubClientError);
		await expect(promise).rejects.toMatchObject({statusCode: 404});
	});

	it('5xx 응답은 GithubClientError로 변환하지 않는다', async () => {
		const axiosError = new AxiosError('Internal Server Error');
		axiosError.response = {data: {}, status: 500} as unknown as AxiosResponse;
		httpService.get.mockReturnValue(throwError(() => axiosError));

		const error = await client
			.listRepositories('my-org')
			.catch((error_: unknown) => error_ as AxiosError);

		expect(error).toBeInstanceOf(AxiosError);
		expect(error).not.toBeInstanceOf(GithubClientError);
	});
});
