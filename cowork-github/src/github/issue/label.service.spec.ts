import { Test, TestingModule } from '@nestjs/testing';
import { GithubApiClient } from '../client/github-api.client';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';
import { LabelService } from './label.service';

describe('LabelService', () => {
  let service: LabelService;
  let apiClient: { listLabels: jest.Mock; createLabel: jest.Mock };

  const dto: CreateIssueDto = {
    owner: 'my-org',
    repo: 'my-repo',
    title: 'Issue title',
  };

  const defaultRepoLabels = [
    'bug',
    'bug:버그',
    'enhancement:개선작업',
    'help wanted:도움 필요',
    'waiting for review:검토 대기',
  ];

  beforeEach(async () => {
    apiClient = {
      listLabels: jest.fn().mockResolvedValue(defaultRepoLabels),
      createLabel: jest.fn().mockResolvedValue(undefined),
    };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        LabelService,
        { provide: GithubApiClient, useValue: apiClient },
      ],
    }).compile();

    service = module.get<LabelService>(LabelService);
  });

  describe('ensureUsableLabels', () => {
    it('repo 라벨이 있으면 새 라벨을 생성하지 않는다', async () => {
      await service.ensureUsableLabels('token', dto);
      expect(apiClient.createLabel).not.toHaveBeenCalled();
    });

    it('요청 labels가 repo에 없으면 생성하고 반환 목록에 포함한다', async () => {
      const result = await service.ensureUsableLabels('token', {
        ...dto,
        labels: ['unknown-label'],
      });

      expect(apiClient.createLabel).toHaveBeenCalledWith(
        'token',
        expect.any(Object),
        expect.objectContaining({ name: 'unknown-label', color: 'ededed' }),
      );
      expect(result).toContain('unknown-label');
    });

    it('요청 labels가 repo에 이미 있으면 생성하지 않는다', async () => {
      await service.ensureUsableLabels('token', { ...dto, labels: ['bug'] });
      expect(apiClient.createLabel).not.toHaveBeenCalled();
    });

    it('repo 라벨이 비어 있으면 cowork 기본 라벨을 생성한다', async () => {
      apiClient.listLabels.mockResolvedValue([]);

      await service.ensureUsableLabels('token', dto);

      expect(apiClient.createLabel).toHaveBeenCalledWith(
        'token',
        expect.any(Object),
        expect.objectContaining({ name: 'bug:버그' }),
      );
    });

    it('라벨 생성 시 422 에러는 이미 존재하는 것으로 간주하고 스킵한다', async () => {
      apiClient.listLabels.mockResolvedValue([]);
      apiClient.createLabel.mockRejectedValue(
        new GithubClientError('already exists', 422),
      );

      await expect(
        service.ensureUsableLabels('token', dto),
      ).resolves.not.toThrow();
    });

    it('라벨 생성 시 403 에러는 권한 없음으로 간주하고 스킵한다', async () => {
      apiClient.listLabels.mockResolvedValue([]);
      apiClient.createLabel.mockRejectedValue(
        new GithubClientError('forbidden', 403),
      );

      await expect(
        service.ensureUsableLabels('token', dto),
      ).resolves.not.toThrow();
    });
  });

  describe('resolveLabels', () => {
    it('요청 labels가 있고 repo에 존재하면 해당 labels를 반환한다', () => {
      const result = service.resolveLabels(
        { ...dto, labels: ['bug'] },
        defaultRepoLabels,
      );
      expect(result).toEqual(['bug']);
    });

    it('요청 labels가 repo에 없으면 키워드 기반으로 추론한다', () => {
      const result = service.resolveLabels(
        { ...dto, title: '로그인 500 에러', labels: ['nonexistent'] },
        defaultRepoLabels,
      );
      expect(result).toEqual(['bug:버그']);
    });

    it('bug 키워드 포함 시 bug 라벨을 반환한다', () => {
      const result = service.resolveLabels(
        { ...dto, title: '로그인 500 에러', body: '카카오 로그인 실패' },
        defaultRepoLabels,
      );
      expect(result).toEqual(['bug:버그']);
    });

    it('애매한 이상 표현은 bug 라벨로 분류한다', () => {
      const result = service.resolveLabels(
        { ...dto, title: '로그인 쪽이 이상해요' },
        defaultRepoLabels,
      );
      expect(result).toEqual(['bug:버그']);
    });

    it('enhancement 키워드 포함 시 enhancement 라벨을 반환한다', () => {
      const result = service.resolveLabels(
        { ...dto, title: '기능 추가 요청' },
        defaultRepoLabels,
      );
      expect(result).toEqual(['enhancement:개선작업']);
    });

    it('repo 라벨명이 prefix 형태면 해당 라벨명으로 매칭한다', () => {
      const result = service.resolveLabels(
        { ...dto, title: '기능 개선' },
        ['enhancement:개선작업'],
      );
      expect(result).toEqual(['enhancement:개선작업']);
    });

    it('키워드가 없으면 fallback 라벨을 반환한다', () => {
      const result = service.resolveLabels(dto, defaultRepoLabels);
      expect(result).toEqual(['help wanted:도움 필요']);
    });

    it('repo에 매칭 라벨이 없으면 빈 배열을 반환한다', () => {
      const result = service.resolveLabels(dto, ['release:릴리즈']);
      expect(result).toEqual([]);
    });
  });
});
