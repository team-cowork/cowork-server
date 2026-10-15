import { Test, TestingModule } from '@nestjs/testing';
import { GithubApiClient } from '../client/github-api.client';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';
import { COWORK_LABELS } from './label.constants';
import { LabelService } from './label.service';

describe('LabelService', () => {
  let service: LabelService;
  let apiClient: { listLabels: jest.Mock; createLabel: jest.Mock };

  const dto: CreateIssueDto = {
    owner: 'my-org',
    repo: 'my-repo',
    title: 'Issue title',
  };

  const allCoworkLabelNames = COWORK_LABELS.map((l) => l.name);

  beforeEach(async () => {
    apiClient = {
      listLabels: jest.fn().mockResolvedValue(allCoworkLabelNames),
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

  // ─── resolveLabels ────────────────────────────────────────────────────────

  describe('resolveLabels', () => {
    describe('키워드 기반 cowork: 라벨', () => {
      it('bug 키워드 포함 시 bug:버그를 반환한다', () => {
        const result = service.resolveLabels({
          ...dto,
          title: '로그인 500 에러',
          body: '카카오 로그인 실패',
        });
        expect(result).toContain('bug:버그');
      });

      it('enhancement 키워드 포함 시 enhancement:개선작업을 반환한다', () => {
        const result = service.resolveLabels({
          ...dto,
          title: '기능 추가 요청',
        });
        expect(result).toContain('enhancement:개선작업');
      });

      it('question 키워드 포함 시 question:질문을 반환한다', () => {
        const result = service.resolveLabels({
          ...dto,
          title: '어떻게 사용하나요?',
        });
        expect(result).toContain('question:질문');
      });

      it('이상 표현은 bug:버그로 분류한다', () => {
        const result = service.resolveLabels({
          ...dto,
          title: '로그인 쪽이 이상해요',
        });
        expect(result).toContain('bug:버그');
      });

      it('bug 키워드가 enhancement보다 우선한다', () => {
        const result = service.resolveLabels({
          ...dto,
          title: '기능 추가 요청인데 오류가 있음',
        });
        expect(result).toContain('bug:버그');
        expect(result).not.toContain('enhancement:개선작업');
      });
    });

    describe('cowork:default fallback', () => {
      it('키워드가 없으면 help wanted:도움 필요을 반환한다', () => {
        const result = service.resolveLabels(dto);
        expect(result).toEqual(['help wanted:도움 필요']);
      });

      it('명시 라벨만 있고 cowork 라벨이 없으면 help wanted:도움 필요을 추가한다', () => {
        const result = service.resolveLabels({ ...dto, labels: ['urgent'] });
        expect(result).toContain('urgent');
        expect(result).toContain('help wanted:도움 필요');
      });
    });

    describe('명시 라벨 처리', () => {
      it('cowork 명시 라벨은 help wanted:도움 필요을 추가하지 않는다', () => {
        const result = service.resolveLabels({
          ...dto,
          labels: ['blocked:차단됨'],
        });
        expect(result).toContain('blocked:차단됨');
        expect(result).not.toContain('help wanted:도움 필요');
      });

      it('명시 라벨과 키워드 결과를 병합한다', () => {
        const result = service.resolveLabels({
          ...dto,
          title: '로그인 에러',
          labels: ['urgent'],
        });
        expect(result).toContain('urgent');
        expect(result).toContain('bug:버그');
        expect(result).not.toContain('help wanted:도움 필요');
      });

      it('명시 라벨과 키워드가 동일한 cowork 라벨이면 중복 없이 1개만 반환한다', () => {
        const result = service.resolveLabels({
          ...dto,
          title: '기능 추가',
          labels: ['enhancement:개선작업'],
        });
        expect(result.filter((l) => l === 'enhancement:개선작업')).toHaveLength(
          1,
        );
      });

      it('빈 문자열 명시 라벨은 무시한다', () => {
        const result = service.resolveLabels({ ...dto, labels: ['', '  '] });
        expect(result).toEqual(['help wanted:도움 필요']);
      });
    });
  });

  // ─── ensureLabelsExist ────────────────────────────────────────────────────

  describe('ensureLabelsExist', () => {
    it('모든 라벨이 레포에 있으면 createLabel을 호출하지 않는다', async () => {
      await service.ensureLabelsExist(dto, ['bug:버그']);
      expect(apiClient.createLabel).not.toHaveBeenCalled();
    });

    it('레포에 없는 cowork 라벨은 정의된 color/description으로 생성한다', async () => {
      apiClient.listLabels.mockResolvedValue([]);

      await service.ensureLabelsExist(dto, ['bug:버그']);

      expect(apiClient.createLabel).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({ name: 'bug:버그', color: 'd73a4a' }),
      );
    });

    it('레포에 없는 커스텀 라벨은 color=ededed로 생성한다', async () => {
      apiClient.listLabels.mockResolvedValue([]);

      await service.ensureLabelsExist(dto, ['urgent']);

      expect(apiClient.createLabel).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({ name: 'urgent', color: 'ededed' }),
      );
    });

    it('라벨 목록이 비어 있으면 listLabels를 호출하지 않는다', async () => {
      await service.ensureLabelsExist(dto, []);
      expect(apiClient.listLabels).not.toHaveBeenCalled();
    });

    it('422 에러는 스킵하고 계속 진행한다', async () => {
      apiClient.listLabels.mockResolvedValue([]);
      apiClient.createLabel.mockRejectedValue(
        new GithubClientError('already exists', 422),
      );

      await expect(
        service.ensureLabelsExist(dto, ['bug:버그']),
      ).resolves.not.toThrow();
    });

    it('403 에러는 스킵하고 계속 진행한다', async () => {
      apiClient.listLabels.mockResolvedValue([]);
      apiClient.createLabel.mockRejectedValue(
        new GithubClientError('forbidden', 403),
      );

      await expect(
        service.ensureLabelsExist(dto, ['bug:버그']),
      ).resolves.not.toThrow();
    });

    it('여러 라벨 중 일부만 없으면 없는 것만 생성한다', async () => {
      apiClient.listLabels.mockResolvedValue(['bug:버그']);

      await service.ensureLabelsExist(dto, [
        'bug:버그',
        'help wanted:도움 필요',
      ]);

      expect(apiClient.createLabel).toHaveBeenCalledTimes(1);
      expect(apiClient.createLabel).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({ name: 'help wanted:도움 필요' }),
      );
    });
  });
});
