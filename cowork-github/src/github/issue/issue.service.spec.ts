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
  let apiClient: {
    createIssue: jest.Mock;
    searchOpenIssuesByTitle: jest.Mock;
    addLabelsToIssue: jest.Mock;
    listLabels: jest.Mock;
    createLabel: jest.Mock;
  };

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
    apiClient = {
      createIssue: jest.fn(),
      searchOpenIssuesByTitle: jest.fn().mockResolvedValue([]),
      addLabelsToIssue: jest.fn().mockResolvedValue(undefined),
      createLabel: jest.fn().mockResolvedValue(undefined),
      listLabels: jest
        .fn()
        .mockResolvedValue([
          'bug',
          'bug:버그',
          'enhancement:개선작업',
          'help wanted:도움 필요',
          'waiting for review:검토 대기',
        ]),
    };

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
    expect(apiClient.searchOpenIssuesByTitle).toHaveBeenCalledWith(
      'token',
      dto,
    );
    expect(apiClient.createIssue).toHaveBeenCalledWith('token', {
      ...dto,
      labels: ['help wanted:도움 필요'],
    });
  });

  it('요청 labels가 없으면 repo 라벨 중 키워드 기반 라벨을 추론해 새 이슈에 붙인다', async () => {
    apiClient.createIssue.mockResolvedValue(createdIssue);

    await service.createIssue({
      ...dto,
      title: '로그인 500 에러',
      body: '카카오 로그인 실패',
    });

    expect(apiClient.createIssue).toHaveBeenCalledWith(
      'token',
      expect.objectContaining({ labels: ['bug:버그'] }),
    );
  });

  it('요청 labels가 있으면 repo에 존재하는 요청 labels만 사용한다', async () => {
    apiClient.createIssue.mockResolvedValue(createdIssue);

    await service.createIssue({
      ...dto,
      title: '로그인 500 에러',
      labels: ['bug'],
    });

    expect(apiClient.createIssue).toHaveBeenCalledWith(
      'token',
      expect.objectContaining({ labels: ['bug'] }),
    );
  });

  it('요청 labels가 repo에 없으면 라벨을 생성하고 사용한다', async () => {
    apiClient.createIssue.mockResolvedValue(createdIssue);

    await service.createIssue({
      ...dto,
      labels: ['unknown'],
    });

    expect(apiClient.createIssue).toHaveBeenCalledWith(
      'token',
      expect.objectContaining({ labels: ['unknown'] }),
    );
    expect(apiClient.createLabel).toHaveBeenCalledWith(
      'token',
      expect.any(Object),
      expect.objectContaining({
        name: 'unknown',
        color: 'ededed',
      }),
    );
  });

  it('repo 라벨이 비어 있으면 cowork 기본 라벨을 생성하고 분류에 사용한다', async () => {
    apiClient.listLabels.mockResolvedValue([]);
    apiClient.createIssue.mockResolvedValue(createdIssue);

    await service.createIssue({
      ...dto,
      title: '로그인 500 에러',
    });

    expect(apiClient.createLabel).toHaveBeenCalledWith(
      'token',
      expect.any(Object),
      expect.objectContaining({ name: 'bug:버그' }),
    );
    expect(apiClient.createIssue).toHaveBeenCalledWith(
      'token',
      expect.objectContaining({ labels: ['bug:버그'] }),
    );
  });

  it('애매한 이상 표현은 bug 라벨로 분류한다', async () => {
    apiClient.createIssue.mockResolvedValue(createdIssue);

    await service.createIssue({
      ...dto,
      title: '로그인 쪽이 이상해요',
    });

    expect(apiClient.createIssue).toHaveBeenCalledWith(
      'token',
      expect.objectContaining({ labels: ['bug:버그'] }),
    );
  });

  it('repo의 실제 라벨명이 prefix 형태면 해당 라벨명을 사용한다', async () => {
    apiClient.createIssue.mockResolvedValue(createdIssue);

    await service.createIssue({
      ...dto,
      title: '기능 추가 요청',
    });

    expect(apiClient.createIssue).toHaveBeenCalledWith(
      'token',
      expect.objectContaining({ labels: ['enhancement:개선작업'] }),
    );
  });

  it('기존 이슈가 있으면 새 이슈를 만들지 않고 라벨만 추가한다', async () => {
    apiClient.searchOpenIssuesByTitle.mockResolvedValue([
      {
        number: 7,
        html_url: 'https://github.com/team-cowork/cowork-github/issues/7',
        title: '로그인 500 에러',
      },
    ]);

    await service.createIssue({
      ...dto,
      title: '로그인 500 에러',
    });

    expect(apiClient.addLabelsToIssue).toHaveBeenCalledWith(
      'token',
      expect.any(Object),
      7,
      ['bug:버그'],
    );
    expect(apiClient.createIssue).not.toHaveBeenCalled();
  });

  it('기존 이슈가 있고 키워드 매칭이 안 되면 fallback 라벨을 추가한다', async () => {
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
      expect.any(Object),
      7,
      ['help wanted:도움 필요'],
    );
    expect(apiClient.createIssue).not.toHaveBeenCalled();
  });

  it('일시적 오류 발생 시 성공할 때까지 재시도한다', async () => {
    apiClient.searchOpenIssuesByTitle
      .mockRejectedValueOnce(new Error('temporary error'))
      .mockRejectedValueOnce(new Error('temporary error'))
      .mockResolvedValueOnce([]);
    apiClient.createIssue.mockResolvedValueOnce(createdIssue);

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
    expect(apiClient.createIssue).not.toHaveBeenCalled();
  });

  it('재시도 사이에 지수 백오프를 적용한다', async () => {
    const sleepSpy = jest.spyOn(service as any, 'sleep');
    apiClient.searchOpenIssuesByTitle
      .mockRejectedValueOnce(new Error('fail'))
      .mockRejectedValueOnce(new Error('fail'))
      .mockResolvedValueOnce([]);
    apiClient.createIssue.mockResolvedValueOnce(createdIssue);

    await service.createIssue(dto);

    expect(sleepSpy).toHaveBeenNthCalledWith(1, 1000); // 2^0 * 1000
    expect(sleepSpy).toHaveBeenNthCalledWith(2, 2000); // 2^1 * 1000
  });
});
