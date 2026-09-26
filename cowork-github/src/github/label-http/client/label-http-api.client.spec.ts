import { Test, TestingModule } from '@nestjs/testing';
import { HttpService } from '@nestjs/axios';
import { AxiosError } from 'axios';
import type { AxiosResponse } from 'axios';
import { of, throwError } from 'rxjs';
import { LabelHttpApiClient } from './label-http-api.client';
import { GithubAuthService } from '../../auth/github-auth.service';
import { GithubClientError } from '../../github.errors';

describe('LabelHttpApiClient', () => {
  let client: LabelHttpApiClient;
  let httpService: { get: jest.Mock; put: jest.Mock };
  let authService: { getInstallationToken: jest.Mock };

  beforeEach(async () => {
    httpService = { get: jest.fn(), put: jest.fn() };
    authService = {
      getInstallationToken: jest.fn().mockResolvedValue('my-token'),
    };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        LabelHttpApiClient,
        { provide: HttpService, useValue: httpService },
        { provide: GithubAuthService, useValue: authService },
      ],
    }).compile();

    client = module.get<LabelHttpApiClient>(LabelHttpApiClient);
  });

  it('라벨 목록을 per_page 파라미터로 조회한다', async () => {
    httpService.get.mockReturnValue(
      of({ data: [{ name: 'bug', color: 'd73a4a' }] }),
    );

    const result = await client.listLabels('my-org', 'my-repo');

    expect(result).toEqual([{ name: 'bug', color: 'd73a4a' }]);
    expect(httpService.get).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/labels',
      expect.objectContaining({
        params: { per_page: 100 },
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('4xx 응답은 GithubClientError로 변환한다', async () => {
    const axiosError = new AxiosError('Not Found');
    axiosError.response = {
      data: { message: 'Not Found' },
      status: 404,
    } as unknown as AxiosResponse;
    httpService.get.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .listLabels('my-org', 'my-repo')
      .catch((e: unknown) => e as GithubClientError);

    expect(error).toBeInstanceOf(GithubClientError);
    expect(error.statusCode).toBe(404);
  });

  it('5xx 응답은 그대로 전파한다', async () => {
    const axiosError = new AxiosError('Internal Server Error');
    axiosError.response = {
      data: { message: 'Internal Server Error' },
      status: 500,
    } as unknown as AxiosResponse;
    httpService.get.mockReturnValue(throwError(() => axiosError));

    await expect(client.listLabels('my-org', 'my-repo')).rejects.toBe(
      axiosError,
    );
  });

  describe('replaceLabels', () => {
    it('PUT으로 라벨 전체를 교체하고 결과를 반환한다', async () => {
      httpService.put.mockReturnValue(
        of({ data: [{ name: 'bug', color: 'd73a4a' }] }),
      );

      const result = await client.replaceLabels('my-org', 'my-repo', 1, [
        'bug',
      ]);

      expect(result).toEqual([{ name: 'bug', color: 'd73a4a' }]);
      expect(httpService.put).toHaveBeenCalledWith(
        'https://api.github.com/repos/my-org/my-repo/issues/1/labels',
        { labels: ['bug'] },
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer my-token',
          }) as unknown,
        }),
      );
    });

    it('4xx 응답은 GithubClientError로 변환한다', async () => {
      const axiosError = new AxiosError('Not Found');
      axiosError.response = {
        data: { message: 'Not Found' },
        status: 404,
      } as unknown as AxiosResponse;
      httpService.put.mockReturnValue(throwError(() => axiosError));

      const error = await client
        .replaceLabels('my-org', 'my-repo', 1, ['bug'])
        .catch((e: unknown) => e as GithubClientError);

      expect(error).toBeInstanceOf(GithubClientError);
      expect(error.statusCode).toBe(404);
    });
  });
});
