import { Test, TestingModule } from '@nestjs/testing';
import { HttpService } from '@nestjs/axios';
import { AxiosError } from 'axios';
import type { AxiosResponse } from 'axios';
import { of, throwError } from 'rxjs';
import { GithubApiClient } from './github-api.client';
import { GithubClientError } from '../github.errors';
import { CreateIssueDto } from '../dto/create-issue.dto';

describe('GithubApiClient', () => {
  let client: GithubApiClient;
  let httpService: { get: jest.Mock; post: jest.Mock };

  const dto: CreateIssueDto = {
    owner: 'my-org',
    repo: 'my-repo',
    title: 'Bug fix',
    body: 'Description',
  };

  beforeEach(async () => {
    httpService = { get: jest.fn(), post: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        GithubApiClient,
        { provide: HttpService, useValue: httpService },
      ],
    }).compile();

    client = module.get<GithubApiClient>(GithubApiClient);
  });

  it('이슈 생성 성공 시 number와 html_url을 반환한다', async () => {
    httpService.post.mockReturnValue(
      of({
        data: {
          number: 42,
          html_url: 'https://github.com/my-org/my-repo/issues/42',
        },
      }),
    );

    const result = await client.createIssue('my-token', dto);

    expect(result).toEqual({
      number: 42,
      html_url: 'https://github.com/my-org/my-repo/issues/42',
    });
    expect(httpService.post).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/issues',
      expect.objectContaining({ title: dto.title, body: dto.body }),
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('4xx 응답은 GithubClientError로 변환하고 statusCode를 포함한다', async () => {
    const axiosError = new AxiosError('Not Found');
    axiosError.response = {
      data: { message: 'Not Found' },
      status: 404,
    } as unknown as AxiosResponse;
    httpService.post.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .createIssue('my-token', dto)
      .catch((e: unknown) => e as GithubClientError);

    expect(error).toBeInstanceOf(GithubClientError);
    expect(error.statusCode).toBe(404);
    expect(error.message).toBe('Not Found');
  });

  it('422 응답도 GithubClientError로 변환한다', async () => {
    const axiosError = new AxiosError('Unprocessable Entity');
    axiosError.response = {
      data: { message: 'Validation Failed' },
      status: 422,
    } as unknown as AxiosResponse;
    httpService.post.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .createIssue('my-token', dto)
      .catch((e: unknown) => e as GithubClientError);

    expect(error).toBeInstanceOf(GithubClientError);
    expect(error.statusCode).toBe(422);
  });

  it('5xx 응답은 GithubClientError로 변환하지 않고 원본 에러를 던진다', async () => {
    const axiosError = new AxiosError('Internal Server Error');
    axiosError.response = { data: {}, status: 500 } as unknown as AxiosResponse;
    httpService.post.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .createIssue('my-token', dto)
      .catch((e: unknown) => e as AxiosError);

    expect(error).toBeInstanceOf(AxiosError);
    expect(error).not.toBeInstanceOf(GithubClientError);
  });

  it('네트워크 에러(응답 없음)는 원본 에러를 그대로 던진다', async () => {
    const networkError = new AxiosError('Network Error');
    httpService.post.mockReturnValue(throwError(() => networkError));

    const error = await client
      .createIssue('my-token', dto)
      .catch((e: unknown) => e as AxiosError);

    expect(error).toBeInstanceOf(AxiosError);
    expect(error).not.toBeInstanceOf(GithubClientError);
  });

  it('open issue를 제목 기준으로 검색한다', async () => {
    httpService.get.mockReturnValue(
      of({
        data: {
          items: [
            {
              number: 7,
              html_url: 'https://github.com/my-org/my-repo/issues/7',
              title: 'Bug fix',
            },
          ],
        },
      }),
    );

    const result = await client.searchOpenIssuesByTitle('my-token', dto);

    expect(result).toEqual([
      {
        number: 7,
        html_url: 'https://github.com/my-org/my-repo/issues/7',
        title: 'Bug fix',
      },
    ]);
    expect(httpService.get).toHaveBeenCalledWith(
      'https://api.github.com/search/issues',
      expect.objectContaining({
        params: {
          q: 'repo:my-org/my-repo is:issue is:open in:title "Bug fix"',
        },
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('기존 이슈에 라벨을 추가한다', async () => {
    httpService.post.mockReturnValue(of({ data: {} }));

    await client.addLabelsToIssue('my-token', dto, 7, ['bug']);

    expect(httpService.post).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/issues/7/labels',
      { labels: ['bug'] },
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('repo 라벨 목록을 조회한다', async () => {
    httpService.get.mockReturnValue(
      of({
        data: [{ name: 'bug' }, { name: 'enhancement:개선작업' }],
      }),
    );

    const result = await client.listLabels('my-token', dto);

    expect(result).toEqual(['bug', 'enhancement:개선작업']);
    expect(httpService.get).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/labels',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('repo에 라벨을 생성한다', async () => {
    httpService.post.mockReturnValue(of({ data: {} }));

    await client.createLabel('my-token', dto, {
      name: 'bug:버그',
      color: 'd73a4a',
      description: '버그 또는 오작동',
    });

    expect(httpService.post).toHaveBeenCalledWith(
      'https://api.github.com/repos/my-org/my-repo/labels',
      {
        name: 'bug:버그',
        color: 'd73a4a',
        description: '버그 또는 오작동',
      },
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer my-token',
        }) as unknown,
      }),
    );
  });

  it('검색 API의 4xx 응답은 GithubClientError로 변환한다', async () => {
    const axiosError = new AxiosError('Validation Failed');
    axiosError.response = {
      data: { message: 'Validation Failed' },
      status: 422,
    } as unknown as AxiosResponse;
    httpService.get.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .searchOpenIssuesByTitle('my-token', dto)
      .catch((e: unknown) => e as GithubClientError);

    expect(error).toBeInstanceOf(GithubClientError);
    expect(error.statusCode).toBe(422);
  });

  it('라벨 추가 API의 5xx 응답은 원본 에러를 던진다', async () => {
    const axiosError = new AxiosError('Internal Server Error');
    axiosError.response = { data: {}, status: 500 } as unknown as AxiosResponse;
    httpService.post.mockReturnValue(throwError(() => axiosError));

    const error = await client
      .addLabelsToIssue('my-token', dto, 7, ['bug'])
      .catch((e: unknown) => e as AxiosError);

    expect(error).toBeInstanceOf(AxiosError);
    expect(error).not.toBeInstanceOf(GithubClientError);
  });
});
