import { Test, TestingModule } from '@nestjs/testing';
import { HttpService } from '@nestjs/axios';
import { AxiosError } from 'axios';
import type { AxiosResponse } from 'axios';
import { of, throwError } from 'rxjs';
import { PullRequestApiClient } from './pull-request-api.client';
import { GithubAuthService } from '../../auth/github-auth.service';
import { GithubClientError } from '../../github.errors';

describe('PullRequestApiClient', () => {
  let client: PullRequestApiClient;
  let httpService: {
    get: jest.Mock;
    post: jest.Mock;
    put: jest.Mock;
    delete: jest.Mock;
  };
  let authService: { getInstallationToken: jest.Mock };

  beforeEach(async () => {
    httpService = {
      get: jest.fn(),
      post: jest.fn(),
      put: jest.fn(),
      delete: jest.fn(),
    };
    authService = {
      getInstallationToken: jest.fn().mockResolvedValue('my-token'),
    };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        PullRequestApiClient,
        { provide: HttpService, useValue: httpService },
        { provide: GithubAuthService, useValue: authService },
      ],
    }).compile();

    client = module.get<PullRequestApiClient>(PullRequestApiClient);
  });

  it('PR 상세를 조회한다', async () => {
    httpService.get.mockReturnValue(of({ data: { number: 1 } }));

    const result = await client.getPullRequest('my-org', 'my-repo', 1);

    expect(result).toEqual({ number: 1 });
    expect(httpService.get).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/pulls/1',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('PR 목록을 state/per_page/sort/direction 파라미터로 조회한다', async () => {
    httpService.get.mockReturnValue(of({ data: [{ number: 1 }] }));

    const result = await client.listPullRequests('my-org', 'my-repo', 'open');

    expect(result).toEqual([{ number: 1 }]);
    expect(httpService.get).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/pulls',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
        params: {
          state: 'open',
          per_page: 100,
          sort: 'created',
          direction: 'desc',
        },
      }),
    );
  });

  it('PR 목록 조회 4xx 응답은 GithubClientError로 변환한다', async () => {
    const axiosError = new AxiosError('Not Found');
    axiosError.response = {
      data: { message: 'Not Found' },
      status: 404,
    } as unknown as AxiosResponse;
    httpService.get.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .listPullRequests('my-org', 'my-repo', 'open')
      .catch((e: unknown) => e as GithubClientError);

    expect(error).toBeInstanceOf(GithubClientError);
    expect(error.statusCode).toBe(404);
  });

  it('머지 시 merge_method를 squash로 호출한다', async () => {
    httpService.put.mockReturnValue(of({ data: {} }));

    await client.mergePullRequest('my-org', 'my-repo', 1);

    expect(httpService.put).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/pulls/1/merge',
      { merge_method: 'squash' },
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('승인 시 event를 APPROVE로 호출한다', async () => {
    httpService.post.mockReturnValue(of({ data: {} }));

    await client.approvePullRequest('my-org', 'my-repo', 1);

    expect(httpService.post).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/pulls/1/reviews',
      { event: 'APPROVE' },
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('협업자 권한을 조회한다', async () => {
    httpService.get.mockReturnValue(of({ data: { permission: 'write' } }));

    const result = await client.getCollaboratorPermission(
      'my-org',
      'my-repo',
      'octocat',
    );

    expect(result).toEqual({ permission: 'write' });
    expect(httpService.get).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/collaborators/octocat/permission',
      expect.anything(),
    );
  });

  it('브랜치를 삭제한다', async () => {
    httpService.delete.mockReturnValue(of({ data: {} }));

    await client.deleteBranch('my-org', 'my-repo', 'feature/foo');

    expect(httpService.delete).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/git/refs/heads/feature/foo',
      expect.anything(),
    );
  });

  it('머지 405 응답은 GithubClientError(405)로 변환한다', async () => {
    const axiosError = new AxiosError('Not Mergeable');
    axiosError.response = {
      data: { message: 'Not Mergeable' },
      status: 405,
    } as unknown as AxiosResponse;
    httpService.put.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .mergePullRequest('my-org', 'my-repo', 1)
      .catch((e: unknown) => e as GithubClientError);

    expect(error).toBeInstanceOf(GithubClientError);
    expect(error.statusCode).toBe(405);
  });

  it('5xx 응답은 GithubClientError로 변환하지 않는다', async () => {
    const axiosError = new AxiosError('Internal Server Error');
    axiosError.response = { data: {}, status: 500 } as unknown as AxiosResponse;
    httpService.get.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .getPullRequest('my-org', 'my-repo', 1)
      .catch((e: unknown) => e as AxiosError);

    expect(error).toBeInstanceOf(AxiosError);
    expect(error).not.toBeInstanceOf(GithubClientError);
  });
});
