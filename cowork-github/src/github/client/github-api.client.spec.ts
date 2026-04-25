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
  let httpService: { post: jest.Mock };

  const dto: CreateIssueDto = {
    owner: 'my-org',
    repo: 'my-repo',
    title: 'Bug fix',
    body: 'Description',
  };

  beforeEach(async () => {
    httpService = { post: jest.fn() };

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
});
