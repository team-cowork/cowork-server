import { Test, TestingModule } from '@nestjs/testing';
import { PullRequestApiClient } from './client/pull-request-api.client';
import { GithubClientError } from '../github.errors';
import { PullRequestService } from './pull-request.service';

describe('PullRequestService', () => {
  let service: PullRequestService;
  let apiClient: {
    getPullRequest: jest.Mock;
    listPullRequests: jest.Mock;
    listPullRequestFiles: jest.Mock;
    listPullRequestReviews: jest.Mock;
    getCollaboratorPermission: jest.Mock;
    mergePullRequest: jest.Mock;
    approvePullRequest: jest.Mock;
    deleteBranch: jest.Mock;
  };

  const basePr = {
    number: 1,
    title: 'Add feature',
    body: 'desc',
    state: 'open',
    merged: false,
    mergeable: true,
    mergeable_state: 'clean',
    html_url: 'https://github.com/my-org/my-repo/pull/1',
    user: { login: 'author' },
    head: { ref: 'feature/foo', repo: { full_name: 'my-org/my-repo' } },
    base: { ref: 'main', repo: { full_name: 'my-org/my-repo' } },
  };

  beforeEach(async () => {
    apiClient = {
      getPullRequest: jest.fn(),
      listPullRequests: jest.fn(),
      listPullRequestFiles: jest.fn(),
      listPullRequestReviews: jest.fn(),
      getCollaboratorPermission: jest.fn(),
      mergePullRequest: jest.fn(),
      approvePullRequest: jest.fn(),
      deleteBranch: jest.fn(),
    };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        PullRequestService,
        { provide: PullRequestApiClient, useValue: apiClient },
      ],
    }).compile();

    service = module.get<PullRequestService>(PullRequestService);
  });

  describe('getPullRequestDetail', () => {
    it('승인 리뷰가 있으면 reviewDecision을 APPROVED로 계산한다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.listPullRequestReviews.mockResolvedValue([
        {
          user: { login: 'reviewer' },
          state: 'APPROVED',
          submitted_at: '2024-01-01T00:00:00Z',
        },
      ]);

      const result = await service.getPullRequestDetail('my-org', 'my-repo', 1);

      expect(result.reviewDecision).toBe('APPROVED');
      expect(result.author).toBe('author');
      expect(result.mergeableState).toBe('clean');
    });

    it('같은 리뷰어의 최신 상태가 CHANGES_REQUESTED면 그 값을 우선한다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.listPullRequestReviews.mockResolvedValue([
        {
          user: { login: 'reviewer' },
          state: 'APPROVED',
          submitted_at: '2024-01-01T00:00:00Z',
        },
        {
          user: { login: 'reviewer' },
          state: 'CHANGES_REQUESTED',
          submitted_at: '2024-01-02T00:00:00Z',
        },
      ]);

      const result = await service.getPullRequestDetail('my-org', 'my-repo', 1);

      expect(result.reviewDecision).toBe('CHANGES_REQUESTED');
    });

    it('리뷰가 없으면 reviewDecision은 null이다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.listPullRequestReviews.mockResolvedValue([]);

      const result = await service.getPullRequestDetail('my-org', 'my-repo', 1);

      expect(result.reviewDecision).toBeNull();
    });

    it('승인이 반려(DISMISSED)되면 그 사용자의 표는 결정에서 제외한다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.listPullRequestReviews.mockResolvedValue([
        {
          user: { login: 'reviewer' },
          state: 'APPROVED',
          submitted_at: '2024-01-01T00:00:00Z',
        },
        {
          user: { login: 'reviewer' },
          state: 'DISMISSED',
          submitted_at: '2024-01-02T00:00:00Z',
        },
      ]);

      const result = await service.getPullRequestDetail('my-org', 'my-repo', 1);

      expect(result.reviewDecision).toBeNull();
    });
  });

  describe('listPullRequests', () => {
    const baseListItem = {
      number: 1,
      title: 'Add feature',
      state: 'closed',
      draft: false,
      merged_at: '2024-01-02T00:00:00Z',
      html_url: 'https://github.com/my-org/my-repo/pull/1',
      user: { login: 'author' },
      labels: [{ name: 'bug' }, { name: 'enhancement' }],
      created_at: '2024-01-01T00:00:00Z',
      updated_at: '2024-01-03T00:00:00Z',
    };

    it('raw 목록 아이템을 요약 DTO로 매핑한다', async () => {
      apiClient.listPullRequests.mockResolvedValue([baseListItem]);

      const result = await service.listPullRequests('my-org', 'my-repo', 'all');

      expect(result).toEqual([
        {
          number: 1,
          title: 'Add feature',
          author: 'author',
          state: 'closed',
          draft: false,
          merged: true,
          htmlUrl: 'https://github.com/my-org/my-repo/pull/1',
          labels: ['bug', 'enhancement'],
          createdAt: '2024-01-01T00:00:00Z',
          updatedAt: '2024-01-03T00:00:00Z',
        },
      ]);
      expect(apiClient.listPullRequests).toHaveBeenCalledWith(
        'my-org',
        'my-repo',
        'all',
      );
    });

    it('merged_at이 null이면 merged는 false다', async () => {
      apiClient.listPullRequests.mockResolvedValue([
        { ...baseListItem, merged_at: null },
      ]);

      const result = await service.listPullRequests(
        'my-org',
        'my-repo',
        'open',
      );

      expect(result[0].merged).toBe(false);
    });

    it('labels가 비어 있으면 빈 배열로 매핑한다', async () => {
      apiClient.listPullRequests.mockResolvedValue([
        { ...baseListItem, labels: [] },
      ]);

      const result = await service.listPullRequests(
        'my-org',
        'my-repo',
        'open',
      );

      expect(result[0].labels).toEqual([]);
    });
  });

  describe('mergePullRequest', () => {
    const params = {
      owner: 'my-org',
      repo: 'my-repo',
      prNumber: 1,
      requesterGithubUsername: 'octocat',
    };

    it('이미 머지된 PR은 권한 검증 없이 idempotent 응답을 반환한다', async () => {
      apiClient.getPullRequest.mockResolvedValue({ ...basePr, merged: true });

      const result = await service.mergePullRequest(params);

      expect(result.alreadyMerged).toBe(true);
      expect(apiClient.getCollaboratorPermission).not.toHaveBeenCalled();
      expect(apiClient.mergePullRequest).not.toHaveBeenCalled();
    });

    it('쓰기 권한이 없으면 403 GithubClientError를 던진다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.getCollaboratorPermission.mockResolvedValue({
        permission: 'read',
      });

      await expect(service.mergePullRequest(params)).rejects.toMatchObject({
        statusCode: 403,
      });
      expect(apiClient.mergePullRequest).not.toHaveBeenCalled();
    });

    it('머지 성공 시 같은 저장소 브랜치는 자동 삭제한다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.getCollaboratorPermission.mockResolvedValue({
        permission: 'write',
      });
      apiClient.mergePullRequest.mockResolvedValue(undefined);
      apiClient.deleteBranch.mockResolvedValue(undefined);

      const result = await service.mergePullRequest(params);

      expect(result.alreadyMerged).toBe(false);
      expect(apiClient.deleteBranch).toHaveBeenCalledWith(
        'my-org',
        'my-repo',
        'feature/foo',
      );
    });

    it('포크에서 온 PR은 브랜치를 삭제하지 않는다', async () => {
      apiClient.getPullRequest.mockResolvedValue({
        ...basePr,
        head: { ref: 'feature/foo', repo: { full_name: 'other/fork' } },
      });
      apiClient.getCollaboratorPermission.mockResolvedValue({
        permission: 'write',
      });
      apiClient.mergePullRequest.mockResolvedValue(undefined);

      await service.mergePullRequest(params);

      expect(apiClient.deleteBranch).not.toHaveBeenCalled();
    });

    it('head.repo와 base.repo가 모두 null이면 브랜치를 삭제하지 않는다', async () => {
      apiClient.getPullRequest.mockResolvedValue({
        ...basePr,
        head: { ref: 'feature/foo', repo: null },
        base: { ref: 'main', repo: null },
      });
      apiClient.getCollaboratorPermission.mockResolvedValue({
        permission: 'write',
      });
      apiClient.mergePullRequest.mockResolvedValue(undefined);

      await service.mergePullRequest(params);

      expect(apiClient.deleteBranch).not.toHaveBeenCalled();
    });

    it('브랜치 삭제 실패는 머지 결과에 영향을 주지 않는다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.getCollaboratorPermission.mockResolvedValue({
        permission: 'write',
      });
      apiClient.mergePullRequest.mockResolvedValue(undefined);
      apiClient.deleteBranch.mockRejectedValue(new Error('branch protected'));

      const result = await service.mergePullRequest(params);

      expect(result.alreadyMerged).toBe(false);
    });

    it('GitHub가 405를 반환하면 mergeable_state를 포함한 409 에러로 변환한다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.getCollaboratorPermission.mockResolvedValue({
        permission: 'write',
      });
      apiClient.mergePullRequest.mockRejectedValue(
        new GithubClientError('Not mergeable', 405),
      );

      const error = await service
        .mergePullRequest(params)
        .catch((e: unknown) => e as GithubClientError);

      expect(error.statusCode).toBe(409);
      expect(error.message).toContain('clean');
    });
  });

  describe('approvePullRequest', () => {
    const params = {
      owner: 'my-org',
      repo: 'my-repo',
      prNumber: 1,
      requesterGithubUsername: 'octocat',
    };

    it('PR 작성자 본인이면 GitHub 호출 없이 403을 던진다', async () => {
      apiClient.getPullRequest.mockResolvedValue({
        ...basePr,
        user: { login: 'octocat' },
      });

      await expect(service.approvePullRequest(params)).rejects.toMatchObject({
        statusCode: 403,
      });
      expect(apiClient.getCollaboratorPermission).not.toHaveBeenCalled();
      expect(apiClient.approvePullRequest).not.toHaveBeenCalled();
    });

    it('대소문자가 달라도 본인 PR이면 차단한다', async () => {
      apiClient.getPullRequest.mockResolvedValue({
        ...basePr,
        user: { login: 'Octocat' },
      });

      await expect(service.approvePullRequest(params)).rejects.toMatchObject({
        statusCode: 403,
      });
    });

    it('쓰기 권한이 없으면 403을 던진다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.getCollaboratorPermission.mockResolvedValue({
        permission: 'none',
      });

      await expect(service.approvePullRequest(params)).rejects.toMatchObject({
        statusCode: 403,
      });
      expect(apiClient.approvePullRequest).not.toHaveBeenCalled();
    });

    it('권한이 충분하고 본인이 아니면 승인한다', async () => {
      apiClient.getPullRequest.mockResolvedValue(basePr);
      apiClient.getCollaboratorPermission.mockResolvedValue({
        permission: 'write',
      });
      apiClient.approvePullRequest.mockResolvedValue(undefined);

      const result = await service.approvePullRequest(params);

      expect(result.prUrl).toBe(basePr.html_url);
      expect(apiClient.approvePullRequest).toHaveBeenCalledWith(
        'my-org',
        'my-repo',
        1,
      );
    });
  });
});
