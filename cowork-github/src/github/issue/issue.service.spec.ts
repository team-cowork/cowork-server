import { Test, TestingModule } from '@nestjs/testing';
import { AppConfigService } from '../../config/app-config.service';
import { GithubAuthService } from '../auth/github-auth.service';
import { GithubApiClient } from '../client/github-api.client';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';
import { IssueService } from './issue.service';
import { LabelService } from './label.service';

describe('IssueService', () => {
  let service: IssueService;
  let authService: { getInstallationToken: jest.Mock };
  let apiClient: {
    createIssue: jest.Mock;
    searchOpenIssuesByTitle: jest.Mock;
    addLabelsToIssue: jest.Mock;
  };
  let labelService: { ensureUsableLabels: jest.Mock; resolveLabels: jest.Mock };

  const dto: CreateIssueDto = {
    owner: 'team-cowork',
    repo: 'cowork-github',
    title: 'Issue title',
  };

  const createdIssue = {
    number: 1,
    html_url: 'https://github.com/team-cowork/cowork-github/issues/1',
  };

  const REPO_LABELS = ['bug:버그', 'help wanted:도움 필요'];
  const RESOLVED_LABELS = ['help wanted:도움 필요'];

  beforeEach(async () => {
    authService = {
      getInstallationToken: jest.fn().mockResolvedValue('token'),
    };
    apiClient = {
      createIssue: jest.fn().mockResolvedValue(createdIssue),
      searchOpenIssuesByTitle: jest.fn().mockResolvedValue([]),
      addLabelsToIssue: jest.fn().mockResolvedValue(undefined),
    };
    labelService = {
      ensureUsableLabels: jest.fn().mockResolvedValue(REPO_LABELS),
      resolveLabels: jest.fn().mockReturnValue(RESOLVED_LABELS),
    };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        IssueService,
        { provide: AppConfigService, useValue: { githubIssueMaxRetries: 3 } },
        { provide: GithubAuthService, useValue: authService },
        { provide: GithubApiClient, useValue: apiClient },
        { provide: LabelService, useValue: labelService },
      ],
    }).compile();

    service = module.get<IssueService>(IssueService);
    jest.spyOn(service as any, 'sleep').mockResolvedValue(undefined);
  });

  it('정상 처리 시 라벨을 해석하고 이슈를 생성한다', async () => {
    await service.createIssue(dto);

    expect(authService.getInstallationToken).toHaveBeenCalledWith(dto.owner);
    expect(labelService.ensureUsableLabels).toHaveBeenCalledWith('token', dto);
    expect(labelService.resolveLabels).toHaveBeenCalledWith(dto, REPO_LABELS);
    expect(apiClient.searchOpenIssuesByTitle).toHaveBeenCalledWith(
      'token',
      dto,
    );
    expect(apiClient.createIssue).toHaveBeenCalledWith('token', {
      ...dto,
      labels: RESOLVED_LABELS,
    });
  });

  it('기존 이슈가 있으면 새 이슈를 만들지 않고 라벨만 추가한다', async () => {
    apiClient.searchOpenIssuesByTitle.mockResolvedValue([
      {
        number: 7,
        html_url: 'https://github.com/team-cowork/cowork-github/issues/7',
        title: 'Issue title',
      },
    ]);

    await service.createIssue(dto);

    expect(apiClient.addLabelsToIssue).toHaveBeenCalledWith(
      'token',
      dto,
      7,
      RESOLVED_LABELS,
    );
    expect(apiClient.createIssue).not.toHaveBeenCalled();
  });

  it('기존 이슈가 있고 라벨이 없으면 addLabelsToIssue를 호출하지 않는다', async () => {
    apiClient.searchOpenIssuesByTitle.mockResolvedValue([
      {
        number: 7,
        html_url: 'https://github.com/team-cowork/cowork-github/issues/7',
        title: 'Issue title',
      },
    ]);
    labelService.resolveLabels.mockReturnValue([]);

    await service.createIssue(dto);

    expect(apiClient.addLabelsToIssue).not.toHaveBeenCalled();
    expect(apiClient.createIssue).not.toHaveBeenCalled();
  });

  it('일시적 오류 발생 시 성공할 때까지 재시도한다', async () => {
    apiClient.searchOpenIssuesByTitle
      .mockRejectedValueOnce(new Error('temporary error'))
      .mockRejectedValueOnce(new Error('temporary error'))
      .mockResolvedValueOnce([]);

    await service.createIssue(dto);

    expect(apiClient.searchOpenIssuesByTitle).toHaveBeenCalledTimes(3);
    expect(apiClient.createIssue).toHaveBeenCalledTimes(1);
  });

  it('GithubClientError는 재시도 없이 즉시 던진다', async () => {
    const error = new GithubClientError('invalid request', 422);
    apiClient.searchOpenIssuesByTitle.mockRejectedValue(error);

    await expect(service.createIssue(dto)).rejects.toThrow(error);
    expect(apiClient.searchOpenIssuesByTitle).toHaveBeenCalledTimes(1);
    expect(apiClient.createIssue).not.toHaveBeenCalled();
  });

  it('최대 재시도 횟수 초과 시 마지막 에러를 던진다', async () => {
    const error = new Error('persistent server error');
    apiClient.searchOpenIssuesByTitle.mockRejectedValue(error);

    await expect(service.createIssue(dto)).rejects.toThrow(error);
    expect(apiClient.searchOpenIssuesByTitle).toHaveBeenCalledTimes(3);
  });

  it('재시도 사이에 지수 백오프를 적용한다', async () => {
    const sleepSpy = jest.spyOn(service as any, 'sleep');
    apiClient.searchOpenIssuesByTitle
      .mockRejectedValueOnce(new Error('fail'))
      .mockRejectedValueOnce(new Error('fail'))
      .mockResolvedValueOnce([]);

    await service.createIssue(dto);

    expect(sleepSpy).toHaveBeenNthCalledWith(1, 1000);
    expect(sleepSpy).toHaveBeenNthCalledWith(2, 2000);
  });
});
