import { Test, TestingModule } from '@nestjs/testing';
import { GithubWebhookController } from './github-webhook.controller';
import { TeamGithubProducer } from '../kafka/team-github.producer';
import { RepoEventProducer } from '../kafka/repo-event.producer';
import { GithubWebhookSignatureGuard } from './github-webhook-signature.guard';

describe('GithubWebhookController', () => {
  let controller: GithubWebhookController;
  let producer: { send: jest.Mock };
  let repoEventProducer: { send: jest.Mock };

  beforeEach(async () => {
    producer = { send: jest.fn() };
    repoEventProducer = { send: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [GithubWebhookController],
      providers: [
        { provide: TeamGithubProducer, useValue: producer },
        { provide: RepoEventProducer, useValue: repoEventProducer },
      ],
    })
      .overrideGuard(GithubWebhookSignatureGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<GithubWebhookController>(GithubWebhookController);
  });

  it('installation.deleted 이벤트는 team.github.disconnected를 발행한다', async () => {
    const result = await controller.handle('installation', {
      action: 'deleted',
      installation: { id: 42 },
    });

    expect(result).toEqual({ ok: true });
    expect(producer.send).toHaveBeenCalledWith('team.github.disconnected', {
      installationId: 42,
    });
  });

  it('installation.created 이벤트는 무시한다', async () => {
    await controller.handle('installation', {
      action: 'created',
      installation: { id: 42 },
    });

    expect(producer.send).not.toHaveBeenCalled();
  });

  it('push 이벤트는 github.repo.event를 발행한다', async () => {
    await controller.handle('push', {
      ref: 'refs/heads/main',
      pusher: { name: 'octocat' },
      commits: [{}, {}],
      repository: { full_name: 'my-org/my-repo' },
    });

    expect(repoEventProducer.send).toHaveBeenCalledWith({
      owner: 'my-org',
      repo: 'my-repo',
      eventType: 'push',
      action: 'pushed',
      summary: '📦 octocat님이 main에 커밋 2개를 푸시했습니다.',
    });
  });

  it('pull_request 이벤트는 github.repo.event를 발행한다', async () => {
    await controller.handle('pull_request', {
      action: 'opened',
      repository: { full_name: 'my-org/my-repo' },
      pull_request: {
        number: 7,
        title: 'Add feature',
        html_url: 'https://github.com/my-org/my-repo/pull/7',
      },
    });

    expect(repoEventProducer.send).toHaveBeenCalledWith({
      owner: 'my-org',
      repo: 'my-repo',
      eventType: 'pull_request',
      action: 'opened',
      summary:
        '🔀 PR #7 opened: Add feature (https://github.com/my-org/my-repo/pull/7)',
    });
  });

  it('repository 정보가 없으면 이벤트를 발행하지 않는다', async () => {
    await controller.handle('push', { ref: 'refs/heads/main' });

    expect(repoEventProducer.send).not.toHaveBeenCalled();
  });

  it('알 수 없는 이벤트는 무시하고 ok를 반환한다', async () => {
    const result = await controller.handle('star', {});

    expect(result).toEqual({ ok: true });
    expect(producer.send).not.toHaveBeenCalled();
    expect(repoEventProducer.send).not.toHaveBeenCalled();
  });
});
