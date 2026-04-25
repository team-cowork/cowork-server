import { Test, TestingModule } from '@nestjs/testing';
import { AppConfigService } from '../../config/app-config.service';
import { GithubAuthService } from '../auth/github-auth.service';
import { GithubApiClient } from '../client/github-api.client';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';
import { IssueService } from './issue.service';

describe('IssueService', () => {
  let service: IssueService;
  let authService: { getInstallationToken: jest.Mock };
  let apiClient: { createIssue: jest.Mock };

  const dto: CreateIssueDto = {
    owner: 'team-cowork',
    repo: 'cowork-github',
    title: 'Issue title',
  };

  const createdIssue = {
    number: 1,
    html_url: 'https://github.com/team-cowork/cowork-github/issues/1',
  };

  beforeEach(async () => {
    authService = {
      getInstallationToken: jest.fn().mockResolvedValue('token'),
    };
    apiClient = { createIssue: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        IssueService,
        { provide: AppConfigService, useValue: { githubIssueMaxRetries: 3 } },
        { provide: GithubAuthService, useValue: authService },
        { provide: GithubApiClient, useValue: apiClient },
      ],
    }).compile();

    service = module.get<IssueService>(IssueService);
    jest.spyOn(service as any, 'sleep').mockResolvedValue(undefined);
  });

  it('정상 처리 시 getInstallationToken과 createIssue를 호출한다', async () => {
    apiClient.createIssue.mockResolvedValue(createdIssue);

    await service.createIssue(dto);

    expect(authService.getInstallationToken).toHaveBeenCalledWith(dto.owner);
    expect(apiClient.createIssue).toHaveBeenCalledWith('token', dto);
  });

  it('일시적 오류 발생 시 성공할 때까지 재시도한다', async () => {
    apiClient.createIssue
      .mockRejectedValueOnce(new Error('temporary error'))
      .mockRejectedValueOnce(new Error('temporary error'))
      .mockResolvedValueOnce(createdIssue);

    await service.createIssue(dto);

    expect(apiClient.createIssue).toHaveBeenCalledTimes(3);
  });

  it('GithubClientError는 재시도 없이 즉시 던진다', async () => {
    const error = new GithubClientError('invalid request', 422);
    apiClient.createIssue.mockRejectedValue(error);

    await expect(service.createIssue(dto)).rejects.toThrow(error);
    expect(apiClient.createIssue).toHaveBeenCalledTimes(1);
  });

  it('최대 재시도 횟수 초과 시 마지막 에러를 던진다', async () => {
    const error = new Error('persistent server error');
    apiClient.createIssue.mockRejectedValue(error);

    await expect(service.createIssue(dto)).rejects.toThrow(error);
    expect(apiClient.createIssue).toHaveBeenCalledTimes(3);
  });

  it('재시도 사이에 지수 백오프를 적용한다', async () => {
    const sleepSpy = jest.spyOn(service as any, 'sleep');
    apiClient.createIssue
      .mockRejectedValueOnce(new Error('fail'))
      .mockRejectedValueOnce(new Error('fail'))
      .mockResolvedValueOnce(createdIssue);

    await service.createIssue(dto);

    expect(sleepSpy).toHaveBeenNthCalledWith(1, 1000); // 2^0 * 1000
    expect(sleepSpy).toHaveBeenNthCalledWith(2, 2000); // 2^1 * 1000
  });
});
