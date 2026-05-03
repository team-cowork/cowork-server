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
  let labelService: { resolveLabels: jest.Mock; ensureLabelsExist: jest.Mock };

  const dto: CreateIssueDto = {
    owner: 'team-cowork',
    repo: 'cowork-github',
    title: 'Issue title',
  };

  const createdIssue = {
    number: 1,
    html_url: 'https://github.com/team-cowork/cowork-github/issues/1',
  };

  const existingIssue = {
    number: 7,
    html_url: 'https://github.com/team-cowork/cowork-github/issues/7',
    title: 'Issue title',
    labels: [],
  };

  const RESOLVED_LABELS = ['default:기본'];

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
      resolveLabels: jest.fn().mockReturnValue(RESOLVED_LABELS),
      ensureLabelsExist: jest.fn().mockResolvedValue(undefined),
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

  it('정상 처리 시 라벨을 해석하고 이슈를 생성한 뒤 issueUrl과 issueNumber를 반환한다', async () => {
    const result = await service.createIssue(dto);

    expect(authService.getInstallationToken).toHaveBeenCalledWith(dto.owner);
    expect(labelService.resolveLabels).toHaveBeenCalledWith(dto);
    expect(labelService.ensureLabelsExist).toHaveBeenCalledWith('token', dto, RESOLVED_LABELS);
    expect(apiClient.searchOpenIssuesByTitle).toHaveBeenCalledWith('token', dto);
    expect(apiClient.createIssue).toHaveBeenCalledWith('token', {
      ...dto,
      labels: RESOLVED_LABELS,
    });
    expect(result).toEqual({
      issueUrl: 'https://github.com/team-cowork/cowork-github/issues/1',
      issueNumber: 1,
    });
  });

  it('resolveLabels가 default:기본을 반환해도 이슈 API에 labels가 전달된다', async () => {
    labelService.resolveLabels.mockReturnValue(['default:기본']);

    await service.createIssue(dto);

    expect(apiClient.createIssue).toHaveBeenCalledWith('token', {
      ...dto,
      labels: ['default:기본'],
    });
  });

  it('기존 이슈가 있으면 새 이슈를 만들지 않고 라벨만 추가한 뒤 기존 이슈 정보를 반환한다', async () => {
    apiClient.searchOpenIssuesByTitle.mockResolvedValue([existingIssue]);

    const result = await service.createIssue(dto);

    expect(apiClient.addLabelsToIssue).toHaveBeenCalledWith(
      'token',
      dto,
      7,
      RESOLVED_LABELS,
    );
    expect(apiClient.createIssue).not.toHaveBeenCalled();
    expect(result).toEqual({
      issueUrl: 'https://github.com/team-cowork/cowork-github/issues/7',
      issueNumber: 7,
    });
  });

  it('기존 이슈가 이미 라벨을 가지고 있으면 중복 라벨 추가를 건너뛴다', async () => {
    apiClient.searchOpenIssuesByTitle.mockResolvedValue([
      { ...existingIssue, labels: [{ name: 'default:기본' }] },
    ]);
    labelService.resolveLabels.mockReturnValue(['default:기본', 'bug:버그']);

    await service.createIssue(dto);

    expect(apiClient.addLabelsToIssue).toHaveBeenCalledWith('token', dto, 7, ['bug:버그']);
    expect(apiClient.createIssue).not.toHaveBeenCalled();
  });

  it('기존 이슈의 라벨이 모두 이미 있으면 addLabelsToIssue를 호출하지 않는다', async () => {
    apiClient.searchOpenIssuesByTitle.mockResolvedValue([
      { ...existingIssue, labels: [{ name: 'default:기본' }] },
    ]);
    labelService.resolveLabels.mockReturnValue(['default:기본']);

    await service.createIssue(dto);

    expect(apiClient.addLabelsToIssue).not.toHaveBeenCalled();
    expect(apiClient.createIssue).not.toHaveBeenCalled();
  });

  it('일시적 오류 발생 시 성공할 때까지 재시도하고 결과를 반환한다', async () => {
    apiClient.searchOpenIssuesByTitle
      .mockRejectedValueOnce(new Error('temporary error'))
      .mockRejectedValueOnce(new Error('temporary error'))
      .mockResolvedValueOnce([]);

    const result = await service.createIssue(dto);

    expect(apiClient.searchOpenIssuesByTitle).toHaveBeenCalledTimes(3);
    expect(apiClient.createIssue).toHaveBeenCalledTimes(1);
    expect(result.issueNumber).toBe(1);
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
