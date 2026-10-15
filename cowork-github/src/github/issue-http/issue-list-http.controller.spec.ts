import { Test, TestingModule } from '@nestjs/testing';
import { HttpException } from '@nestjs/common';
import { IssueListHttpController } from './issue-list-http.controller';
import { IssueHttpService } from './issue-http.service';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';

describe('IssueListHttpController', () => {
  let controller: IssueListHttpController;
  let issueHttpService: { listIssues: jest.Mock };

  beforeEach(async () => {
    issueHttpService = { listIssues: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [IssueListHttpController],
      providers: [{ provide: IssueHttpService, useValue: issueHttpService }],
    })
      .overrideGuard(InternalApiKeyGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<IssueListHttpController>(IssueListHttpController);
  });

  it('이슈 목록을 조회한다', async () => {
    issueHttpService.listIssues.mockResolvedValue([{ number: 1 }]);

    const result = await controller.list('my-org', 'my-repo', 'all');

    expect(result).toEqual([{ number: 1 }]);
    expect(issueHttpService.listIssues).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      'all',
    );
  });

  it('state 기본값은 open이다', async () => {
    issueHttpService.listIssues.mockResolvedValue([]);

    await controller.list('my-org', 'my-repo');

    expect(issueHttpService.listIssues).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      'open',
    );
  });

  it('GithubClientError는 동일한 statusCode의 HttpException으로 변환한다', async () => {
    issueHttpService.listIssues.mockRejectedValue(
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
    issueHttpService.listIssues.mockRejectedValue(new Error('network down'));

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
