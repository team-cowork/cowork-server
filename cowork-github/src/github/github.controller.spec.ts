import { Test, TestingModule } from '@nestjs/testing';
import { KafkaContext } from '@nestjs/microservices';
import { GithubController } from './github.controller';
import { IssueService } from './issue/issue.service';
import { IssueResultProducer } from './kafka/issue-result.producer';
import { GithubClientError } from './github.errors';

describe('GithubController', () => {
  let controller: GithubController;
  let issueService: { createIssue: jest.Mock };
  let issueResultProducer: { send: jest.Mock };
  let commitOffsets: jest.Mock;
  let ctx: KafkaContext;

  beforeEach(async () => {
    issueService = { createIssue: jest.fn() };
    issueResultProducer = { send: jest.fn().mockResolvedValue(undefined) };
    commitOffsets = jest.fn().mockResolvedValue(undefined);
    ctx = {
      getMessage: jest.fn().mockReturnValue({ offset: '5' }),
      getConsumer: jest.fn().mockReturnValue({ commitOffsets }),
      getTopic: jest.fn().mockReturnValue('github.issue.create'),
      getPartition: jest.fn().mockReturnValue(0),
    } as unknown as KafkaContext;

    const module: TestingModule = await Test.createTestingModule({
      controllers: [GithubController],
      providers: [
        { provide: IssueService, useValue: issueService },
        { provide: IssueResultProducer, useValue: issueResultProducer },
      ],
    }).compile();

    controller = module.get<GithubController>(GithubController);
    (controller as unknown as { exitProcess: jest.Mock }).exitProcess = jest.fn();
  });

  const validPayload = { owner: 'my-org', repo: 'my-repo', title: 'Bug fix' };
  const payloadWithContext = { ...validPayload, channelId: 5, teamId: 1 };

  describe('정상 처리', () => {
    it('이슈 생성 후 오프셋을 커밋한다', async () => {
      issueService.createIssue.mockResolvedValue({
        issueUrl: 'https://github.com/my-org/my-repo/issues/1',
        issueNumber: 1,
      });

      await controller.handleIssueCreate(validPayload, ctx);

      expect(issueService.createIssue).toHaveBeenCalledTimes(1);
      expect(commitOffsets).toHaveBeenCalledWith([
        { topic: 'github.issue.create', partition: 0, offset: '6' },
      ]);
    });

    it('channelId와 teamId가 있으면 성공 결과 이벤트를 발행한다', async () => {
      issueService.createIssue.mockResolvedValue({
        issueUrl: 'https://github.com/my-org/my-repo/issues/1',
        issueNumber: 1,
      });

      await controller.handleIssueCreate(payloadWithContext, ctx);

      expect(issueResultProducer.send).toHaveBeenCalledWith({
        channelId: 5,
        teamId: 1,
        success: true,
        issueUrl: 'https://github.com/my-org/my-repo/issues/1',
        issueNumber: 1,
      });
    });

    it('channelId가 없으면 결과 이벤트를 발행하지 않는다', async () => {
      issueService.createIssue.mockResolvedValue({
        issueUrl: 'https://github.com/my-org/my-repo/issues/1',
        issueNumber: 1,
      });

      await controller.handleIssueCreate(validPayload, ctx);

      expect(issueResultProducer.send).not.toHaveBeenCalled();
    });
  });

  describe('검증 실패 (잘못된 페이로드)', () => {
    it('필수 필드 누락 시 서비스 호출 없이 오프셋을 커밋한다', async () => {
      await controller.handleIssueCreate({ owner: 'my-org' }, ctx);

      expect(issueService.createIssue).not.toHaveBeenCalled();
      expect(commitOffsets).toHaveBeenCalledTimes(1);
    });

    it('payload가 객체가 아니면 서비스 호출 없이 오프셋을 커밋한다', async () => {
      await controller.handleIssueCreate(null, ctx);

      expect(issueService.createIssue).not.toHaveBeenCalled();
      expect(commitOffsets).toHaveBeenCalledTimes(1);
    });

    it('channelId와 teamId가 있으면 검증 실패 시 실패 결과 이벤트를 발행한다', async () => {
      const invalidPayloadWithContext = { owner: 'my-org', channelId: 5, teamId: 1 };

      await controller.handleIssueCreate(invalidPayloadWithContext, ctx);

      expect(issueResultProducer.send).toHaveBeenCalledWith(
        expect.objectContaining({ channelId: 5, teamId: 1, success: false }),
      );
    });
  });

  describe('GitHub 클라이언트 에러 (4xx)', () => {
    it('GithubClientError 발생 시 오프셋을 커밋한다', async () => {
      issueService.createIssue.mockRejectedValue(
        new GithubClientError('Repo not found', 404),
      );

      await controller.handleIssueCreate(validPayload, ctx);

      expect(commitOffsets).toHaveBeenCalledTimes(1);
    });

    it('channelId와 teamId가 있으면 클라이언트 에러 시 실패 결과 이벤트를 발행한다', async () => {
      issueService.createIssue.mockRejectedValue(
        new GithubClientError('Repo not found', 404),
      );

      await controller.handleIssueCreate(payloadWithContext, ctx);

      expect(issueResultProducer.send).toHaveBeenCalledWith({
        channelId: 5,
        teamId: 1,
        success: false,
        error: 'Repo not found',
      });
      expect(commitOffsets).toHaveBeenCalledTimes(1);
    });
  });

  describe('GitHub 서버 에러 (5xx)', () => {
    it('서버 에러 발생 시 프로세스를 종료한다', async () => {
      issueService.createIssue.mockRejectedValue(new Error('GitHub 503'));

      await expect(
        controller.handleIssueCreate(validPayload, ctx),
      ).rejects.toThrow('GitHub 503');

      expect(commitOffsets).not.toHaveBeenCalled();
      expect(
        (controller as unknown as { exitProcess: jest.Mock }).exitProcess,
      ).toHaveBeenCalledWith(1);
    });
  });
});
