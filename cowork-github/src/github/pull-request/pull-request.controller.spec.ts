import { Test, TestingModule } from '@nestjs/testing';
import { KafkaContext } from '@nestjs/microservices';
import { PullRequestController } from './pull-request.controller';
import { PullRequestService } from './pull-request.service';
import { PullRequestResultProducer } from './kafka/pull-request-result.producer';
import { GithubClientError } from '../github.errors';

describe('PullRequestController', () => {
  let controller: PullRequestController;
  let pullRequestService: {
    mergePullRequest: jest.Mock;
    approvePullRequest: jest.Mock;
  };
  let resultProducer: { send: jest.Mock };
  let commitOffsets: jest.Mock;
  let ctx: KafkaContext;

  beforeEach(async () => {
    pullRequestService = {
      mergePullRequest: jest.fn(),
      approvePullRequest: jest.fn(),
    };
    resultProducer = { send: jest.fn().mockResolvedValue(undefined) };
    commitOffsets = jest.fn().mockResolvedValue(undefined);
    ctx = {
      getMessage: jest.fn().mockReturnValue({ offset: '5' }),
      getConsumer: jest.fn().mockReturnValue({ commitOffsets }),
      getTopic: jest.fn().mockReturnValue('github.pr.merge'),
      getPartition: jest.fn().mockReturnValue(0),
    } as unknown as KafkaContext;

    const module: TestingModule = await Test.createTestingModule({
      controllers: [PullRequestController],
      providers: [
        { provide: PullRequestService, useValue: pullRequestService },
        { provide: PullRequestResultProducer, useValue: resultProducer },
      ],
    }).compile();

    controller = module.get<PullRequestController>(PullRequestController);
    (controller as unknown as { exitProcess: jest.Mock }).exitProcess =
      jest.fn();
  });

  const validPayload = {
    owner: 'my-org',
    repo: 'my-repo',
    prNumber: 1,
    requesterGithubUsername: 'octocat',
  };
  const payloadWithContext = { ...validPayload, channelId: 5, teamId: 1 };

  describe('handleMerge', () => {
    it('머지 성공 후 오프셋을 커밋한다', async () => {
      pullRequestService.mergePullRequest.mockResolvedValue({
        alreadyMerged: false,
        prUrl: 'https://github.com/my-org/my-repo/pull/1',
        prNumber: 1,
      });

      await controller.handleMerge(validPayload, ctx);

      expect(pullRequestService.mergePullRequest).toHaveBeenCalledTimes(1);
      expect(commitOffsets).toHaveBeenCalledWith([
        { topic: 'github.pr.merge', partition: 0, offset: '6' },
      ]);
    });

    it('channelId/teamId가 있으면 성공 결과 이벤트를 github.pr.merge.result로 보낸다', async () => {
      pullRequestService.mergePullRequest.mockResolvedValue({
        alreadyMerged: false,
        prUrl: 'https://github.com/my-org/my-repo/pull/1',
        prNumber: 1,
      });

      await controller.handleMerge(payloadWithContext, ctx);

      expect(resultProducer.send).toHaveBeenCalledWith(
        'github.pr.merge.result',
        {
          channelId: 5,
          teamId: 1,
          success: true,
          prNumber: 1,
          prUrl: 'https://github.com/my-org/my-repo/pull/1',
        },
      );
    });

    it('필수 필드 누락 시 서비스 호출 없이 오프셋을 커밋한다', async () => {
      await controller.handleMerge({ owner: 'my-org' }, ctx);

      expect(pullRequestService.mergePullRequest).not.toHaveBeenCalled();
      expect(commitOffsets).toHaveBeenCalledTimes(1);
    });

    it('GithubClientError(4xx) 발생 시 실패 결과 이벤트를 보내고 오프셋을 커밋한다', async () => {
      pullRequestService.mergePullRequest.mockRejectedValue(
        new GithubClientError('이 저장소에 대한 쓰기 권한이 없습니다.', 403),
      );

      await controller.handleMerge(payloadWithContext, ctx);

      expect(resultProducer.send).toHaveBeenCalledWith(
        'github.pr.merge.result',
        expect.objectContaining({ success: false }),
      );
      expect(commitOffsets).toHaveBeenCalledTimes(1);
    });

    it('5xx 에러 발생 시 프로세스를 종료하고 오프셋을 커밋하지 않는다', async () => {
      pullRequestService.mergePullRequest.mockRejectedValue(
        new Error('GitHub 503'),
      );

      await expect(controller.handleMerge(validPayload, ctx)).rejects.toThrow(
        'GitHub 503',
      );

      expect(commitOffsets).not.toHaveBeenCalled();
      expect(
        (controller as unknown as { exitProcess: jest.Mock }).exitProcess,
      ).toHaveBeenCalledWith(1);
    });
  });

  describe('handleApprove', () => {
    it('승인 성공 후 github.pr.approve.result로 결과를 보낸다', async () => {
      pullRequestService.approvePullRequest.mockResolvedValue({
        prUrl: 'https://github.com/my-org/my-repo/pull/1',
        prNumber: 1,
      });

      await controller.handleApprove(payloadWithContext, ctx);

      expect(resultProducer.send).toHaveBeenCalledWith(
        'github.pr.approve.result',
        expect.objectContaining({ success: true, prNumber: 1 }),
      );
    });
  });
});
