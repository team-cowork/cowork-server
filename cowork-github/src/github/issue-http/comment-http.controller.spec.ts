import { Test, TestingModule } from '@nestjs/testing';
import { HttpException } from '@nestjs/common';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';
import { CommentHttpController } from './comment-http.controller';
import { IssueHttpService } from './issue-http.service';

describe('CommentHttpController', () => {
  let controller: CommentHttpController;
  let issueHttpService: {
    getComment: jest.Mock;
    updateComment: jest.Mock;
    deleteComment: jest.Mock;
  };

  beforeEach(async () => {
    issueHttpService = {
      getComment: jest.fn(),
      updateComment: jest.fn(),
      deleteComment: jest.fn(),
    };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [CommentHttpController],
      providers: [{ provide: IssueHttpService, useValue: issueHttpService }],
    })
      .overrideGuard(InternalApiKeyGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<CommentHttpController>(CommentHttpController);
  });

  it('댓글 단건을 조회한다', async () => {
    issueHttpService.getComment.mockResolvedValue({ id: 100 });

    const result = await controller.getDetail('my-org', 'my-repo', 100);

    expect(result).toEqual({ id: 100 });
    expect(issueHttpService.getComment).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      100,
    );
  });

  it('댓글 수정 요청을 전달한다', async () => {
    issueHttpService.updateComment.mockResolvedValue({ id: 100 });

    const result = await controller.update('my-org', 'my-repo', 100, {
      body: '수정된 내용',
    });

    expect(result).toEqual({ id: 100 });
    expect(issueHttpService.updateComment).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      100,
      '수정된 내용',
    );
  });

  it('댓글 삭제 요청을 전달한다', async () => {
    issueHttpService.deleteComment.mockResolvedValue(undefined);

    await controller.remove('my-org', 'my-repo', 100);

    expect(issueHttpService.deleteComment).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      100,
    );
  });

  it('GithubClientError는 동일한 statusCode의 HttpException으로 변환한다', async () => {
    issueHttpService.getComment.mockRejectedValue(
      new GithubClientError('댓글을 찾을 수 없습니다.', 404),
    );

    await expect(
      controller.getDetail('my-org', 'my-repo', 100),
    ).rejects.toMatchObject({
      response: '댓글을 찾을 수 없습니다.',
      status: 404,
    });
  });

  it('알 수 없는 에러는 502로 변환한다', async () => {
    issueHttpService.getComment.mockRejectedValue(new Error('network down'));

    let caught: unknown;
    try {
      await controller.getDetail('my-org', 'my-repo', 100);
    } catch (e) {
      caught = e;
    }

    expect(caught).toBeInstanceOf(HttpException);
    expect((caught as HttpException).getStatus()).toBe(502);
  });
});
