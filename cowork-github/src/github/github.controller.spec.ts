import { Test, TestingModule } from '@nestjs/testing';
import { KafkaContext } from '@nestjs/microservices';
import { GithubController } from './github.controller';
import { IssueService } from './issue/issue.service';
import { GithubClientError } from './github.errors';

describe('GithubController', () => {
  let controller: GithubController;
  let issueService: { createIssue: jest.Mock };
  let commitOffsets: jest.Mock;
  let ctx: KafkaContext;

  beforeEach(async () => {
    issueService = { createIssue: jest.fn() };
    commitOffsets = jest.fn().mockResolvedValue(undefined);
    ctx = {
      getMessage: jest.fn().mockReturnValue({ offset: '5' }),
      getConsumer: jest.fn().mockReturnValue({ commitOffsets }),
      getTopic: jest.fn().mockReturnValue('github.issue.create'),
      getPartition: jest.fn().mockReturnValue(0),
    } as unknown as KafkaContext;

    const module: TestingModule = await Test.createTestingModule({
      controllers: [GithubController],
      providers: [{ provide: IssueService, useValue: issueService }],
    }).compile();

    controller = module.get<GithubController>(GithubController);
  });

  const validPayload = { owner: 'my-org', repo: 'my-repo', title: 'Bug fix' };

  it('정상 처리 시 오프셋을 커밋한다', async () => {
    issueService.createIssue.mockResolvedValue(undefined);

    await controller.handleIssueCreate(validPayload, ctx);

    expect(issueService.createIssue).toHaveBeenCalledTimes(1);
    expect(commitOffsets).toHaveBeenCalledWith([
      { topic: 'github.issue.create', partition: 0, offset: '6' },
    ]);
  });

  it('필수 필드 누락 시 서비스 호출 없이 오프셋을 커밋한다 (잘못된 메시지 스킵)', async () => {
    await controller.handleIssueCreate({ owner: 'my-org' }, ctx);

    expect(issueService.createIssue).not.toHaveBeenCalled();
    expect(commitOffsets).toHaveBeenCalledTimes(1);
  });

  it('GithubClientError 발생 시 오프셋을 커밋한다 (4xx 스킵)', async () => {
    issueService.createIssue.mockRejectedValue(
      new GithubClientError('Repo not found', 404),
    );

    await controller.handleIssueCreate(validPayload, ctx);

    expect(commitOffsets).toHaveBeenCalledTimes(1);
  });

  it('서버 에러 발생 시 오프셋을 커밋하지 않는다 (Kafka 재처리 유도)', async () => {
    issueService.createIssue.mockRejectedValue(new Error('GitHub 503'));

    await controller.handleIssueCreate(validPayload, ctx);

    expect(commitOffsets).not.toHaveBeenCalled();
  });
});
