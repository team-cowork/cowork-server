import { Test, TestingModule } from '@nestjs/testing';
import { IssueHttpApiClient } from './client/issue-http-api.client';
import { IssueHttpService } from './issue-http.service';

describe('IssueHttpService', () => {
  let service: IssueHttpService;
  let apiClient: {
    getIssue: jest.Mock;
    listComments: jest.Mock;
    createComment: jest.Mock;
    getComment: jest.Mock;
    updateComment: jest.Mock;
    deleteComment: jest.Mock;
  };

  const baseIssue = {
    number: 1,
    title: 'Bug report',
    state: 'open',
    html_url: 'https://github.com/my-org/my-repo/issues/1',
    user: { login: 'author' },
    labels: [{ name: 'bug' }],
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-02T00:00:00Z',
  };

  const baseComment = {
    id: 100,
    body: 'hello',
    html_url: 'https://github.com/my-org/my-repo/issues/1#issuecomment-100',
    user: { login: 'cowork-bot[bot]' },
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
  };

  beforeEach(async () => {
    apiClient = {
      getIssue: jest.fn(),
      listComments: jest.fn(),
      createComment: jest.fn(),
      getComment: jest.fn(),
      updateComment: jest.fn(),
      deleteComment: jest.fn(),
    };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        IssueHttpService,
        { provide: IssueHttpApiClient, useValue: apiClient },
      ],
    }).compile();

    service = module.get<IssueHttpService>(IssueHttpService);
  });

  describe('getIssueDetail', () => {
    it('raw 이슈를 응답 DTO로 매핑한다', async () => {
      apiClient.getIssue.mockResolvedValue(baseIssue);

      const result = await service.getIssueDetail('my-org', 'my-repo', 1);

      expect(result).toEqual({
        number: 1,
        title: 'Bug report',
        author: 'author',
        state: 'open',
        htmlUrl: 'https://github.com/my-org/my-repo/issues/1',
        labels: ['bug'],
        createdAt: '2024-01-01T00:00:00Z',
        updatedAt: '2024-01-02T00:00:00Z',
      });
    });

    it('user가 null이면 author를 ghost로 매핑한다', async () => {
      apiClient.getIssue.mockResolvedValue({ ...baseIssue, user: null });

      const result = await service.getIssueDetail('my-org', 'my-repo', 1);

      expect(result.author).toBe('ghost');
    });
  });

  describe('createComment', () => {
    it('본문 끝에 작성자 마커를 붙여 GitHub에 전달하고, 응답에는 실제 작성자를 노출한다', async () => {
      apiClient.createComment.mockImplementation(
        (owner: string, repo: string, issueNumber: number, body: string) =>
          Promise.resolve({ ...baseComment, body }),
      );

      const result = await service.createComment(
        'my-org',
        'my-repo',
        1,
        '확인했습니다',
        'octocat',
      );

      expect(apiClient.createComment).toHaveBeenCalledWith(
        'my-org',
        'my-repo',
        1,
        expect.stringContaining('<!-- cowork:author=octocat -->'),
      );
      expect(result.author).toBe('octocat');
      expect(result.body).toBe('확인했습니다');
    });
  });

  describe('listComments / getComment', () => {
    it('마커가 있으면 author를 마커 값으로, body는 마커를 제거한 값으로 반환한다', async () => {
      apiClient.listComments.mockResolvedValue([
        {
          ...baseComment,
          body: '확인했습니다\n\n<!-- cowork:author=octocat -->',
        },
      ]);

      const result = await service.listComments('my-org', 'my-repo', 1);

      expect(result[0].author).toBe('octocat');
      expect(result[0].body).toBe('확인했습니다');
    });

    it('마커가 없으면 GitHub user.login을 author로 사용한다', async () => {
      apiClient.getComment.mockResolvedValue(baseComment);

      const result = await service.getComment('my-org', 'my-repo', 100);

      expect(result.author).toBe('cowork-bot[bot]');
      expect(result.body).toBe('hello');
    });
  });

  describe('updateComment', () => {
    it('기존 댓글의 작성자 마커를 그대로 유지한다', async () => {
      apiClient.getComment.mockResolvedValue({
        ...baseComment,
        body: '이전 내용\n\n<!-- cowork:author=octocat -->',
      });
      apiClient.updateComment.mockImplementation(
        (owner: string, repo: string, commentId: number, body: string) =>
          Promise.resolve({ ...baseComment, body }),
      );

      const result = await service.updateComment(
        'my-org',
        'my-repo',
        100,
        '수정된 내용',
      );

      expect(apiClient.updateComment).toHaveBeenCalledWith(
        'my-org',
        'my-repo',
        100,
        expect.stringContaining('<!-- cowork:author=octocat -->'),
      );
      expect(result.author).toBe('octocat');
      expect(result.body).toBe('수정된 내용');
    });
  });

  describe('deleteComment', () => {
    it('삭제 요청을 그대로 전달한다', async () => {
      apiClient.deleteComment.mockResolvedValue(undefined);

      await service.deleteComment('my-org', 'my-repo', 100);

      expect(apiClient.deleteComment).toHaveBeenCalledWith(
        'my-org',
        'my-repo',
        100,
      );
    });
  });
});
