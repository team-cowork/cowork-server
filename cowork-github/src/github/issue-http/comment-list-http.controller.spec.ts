import { Test, TestingModule } from '@nestjs/testing';
import { HttpException } from '@nestjs/common';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';
import { CommentListHttpController } from './comment-list-http.controller';
import { IssueHttpService } from './issue-http.service';

describe('CommentListHttpController', () => {
  let controller: CommentListHttpController;
  let issueHttpService: {
    listComments: jest.Mock;
    createComment: jest.Mock;
  };

  beforeEach(async () => {
    issueHttpService = {
      listComments: jest.fn(),
      createComment: jest.fn(),
    };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [CommentListHttpController],
      providers: [{ provide: IssueHttpService, useValue: issueHttpService }],
    })
      .overrideGuard(InternalApiKeyGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<CommentListHttpController>(
      CommentListHttpController,
    );
  });

  it('댓글 목록을 조회한다', async () => {
    issueHttpService.listComments.mockResolvedValue([{ id: 1 }]);

    const result = await controller.list('my-org', 'my-repo', 1);

    expect(result).toEqual([{ id: 1 }]);
    expect(issueHttpService.listComments).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      1,
    );
  });

  it('댓글 생성 요청을 전달한다', async () => {
    issueHttpService.createComment.mockResolvedValue({ id: 1 });

    const result = await controller.create('my-org', 'my-repo', 1, {
      body: '확인했습니다',
      requesterGithubUsername: 'octocat',
    });

    expect(result).toEqual({ id: 1 });
    expect(issueHttpService.createComment).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      1,
      '확인했습니다',
      'octocat',
    );
  });

  it('GithubClientError는 동일한 statusCode의 HttpException으로 변환한다', async () => {
    issueHttpService.listComments.mockRejectedValue(
      new GithubClientError('이슈를 찾을 수 없습니다.', 404),
    );

    await expect(controller.list('my-org', 'my-repo', 1)).rejects.toMatchObject(
      { response: '이슈를 찾을 수 없습니다.', status: 404 },
    );
  });

  it('알 수 없는 에러는 502로 변환한다', async () => {
    issueHttpService.listComments.mockRejectedValue(new Error('network down'));

    let caught: unknown;
    try {
      await controller.list('my-org', 'my-repo', 1);
    } catch (e) {
      caught = e;
    }

    expect(caught).toBeInstanceOf(HttpException);
    expect((caught as HttpException).getStatus()).toBe(502);
  });
});
