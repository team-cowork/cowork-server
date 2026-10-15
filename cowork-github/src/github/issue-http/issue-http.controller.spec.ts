import { Test, TestingModule } from '@nestjs/testing';
import { HttpException } from '@nestjs/common';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';
import { IssueHttpController } from './issue-http.controller';
import { IssueHttpService } from './issue-http.service';

describe('IssueHttpController', () => {
  let controller: IssueHttpController;
  let issueHttpService: { getIssueDetail: jest.Mock };

  beforeEach(async () => {
    issueHttpService = { getIssueDetail: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [IssueHttpController],
      providers: [{ provide: IssueHttpService, useValue: issueHttpService }],
    })
      .overrideGuard(InternalApiKeyGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<IssueHttpController>(IssueHttpController);
  });

  it('이슈 상세를 조회한다', async () => {
    issueHttpService.getIssueDetail.mockResolvedValue({ number: 1 });

    const result = await controller.getDetail('my-org', 'my-repo', 1);

    expect(result).toEqual({ number: 1 });
    expect(issueHttpService.getIssueDetail).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
      1,
    );
  });

  it('GithubClientError는 동일한 statusCode의 HttpException으로 변환한다', async () => {
    issueHttpService.getIssueDetail.mockRejectedValue(
      new GithubClientError('이슈를 찾을 수 없습니다.', 404),
    );

    await expect(
      controller.getDetail('my-org', 'my-repo', 1),
    ).rejects.toMatchObject({
      response: '이슈를 찾을 수 없습니다.',
      status: 404,
    });
  });

  it('알 수 없는 에러는 502로 변환한다', async () => {
    issueHttpService.getIssueDetail.mockRejectedValue(
      new Error('network down'),
    );

    let caught: unknown;
    try {
      await controller.getDetail('my-org', 'my-repo', 1);
    } catch (e) {
      caught = e;
    }

    expect(caught).toBeInstanceOf(HttpException);
    expect((caught as HttpException).getStatus()).toBe(502);
  });
});
