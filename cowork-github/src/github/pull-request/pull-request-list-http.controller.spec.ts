import { Test, TestingModule } from '@nestjs/testing';
import { HttpException } from '@nestjs/common';
import { PullRequestListHttpController } from './pull-request-list-http.controller';
import { PullRequestService } from './pull-request.service';
import { InternalApiKeyGuard } from './guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';

describe('PullRequestListHttpController', () => {
  let controller: PullRequestListHttpController;
  let pullRequestService: {
    listPullRequests: jest.Mock;
  };

  beforeEach(async () => {
    pullRequestService = {
      listPullRequests: jest.fn(),
    };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [PullRequestListHttpController],
      providers: [
        { provide: PullRequestService, useValue: pullRequestService },
      ],
    })
      .overrideGuard(InternalApiKeyGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<PullRequestListHttpController>(
      PullRequestListHttpController,
    );
  });

  it('PR 목록을 조회한다', async () => {
    pullRequestService.listPullRequests.mockResolvedValue([{ number: 1 }]);

    const result = await controller.list('my-org', 'my-repo', 'all');

    expect(result).toEqual([{ number: 1 }]);
    expect(pullRequestService.listPullRequests).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      'all',
    );
  });

  it('state 기본값은 open이다', async () => {
    pullRequestService.listPullRequests.mockResolvedValue([]);

    await controller.list('my-org', 'my-repo');

    expect(pullRequestService.listPullRequests).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      'open',
    );
  });

  it('GithubClientError는 동일한 statusCode의 HttpException으로 변환한다', async () => {
    pullRequestService.listPullRequests.mockRejectedValue(
      new GithubClientError('저장소를 찾을 수 없습니다.', 404),
    );

    await expect(
      controller.list('my-org', 'my-repo', 'open'),
    ).rejects.toMatchObject({
      response: '저장소를 찾을 수 없습니다.',
      status: 404,
    });
  });

  it('알 수 없는 에러는 502로 변환한다', async () => {
    pullRequestService.listPullRequests.mockRejectedValue(
      new Error('network down'),
    );

    let caught: unknown;
    try {
      await controller.list('my-org', 'my-repo', 'open');
    } catch (e) {
      caught = e;
    }

    expect(caught).toBeInstanceOf(HttpException);
    expect((caught as HttpException).getStatus()).toBe(502);
  });
});
