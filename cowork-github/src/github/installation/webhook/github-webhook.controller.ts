import {
  Body,
  Controller,
  Headers,
  HttpCode,
  Logger,
  Post,
  UseGuards,
} from '@nestjs/common';
import { RepoEventProducer } from '../kafka/repo-event.producer';
import { TeamGithubProducer } from '../kafka/team-github.producer';
import type { GithubWebhookPayload } from './github-webhook-payload';
import { GithubWebhookSignatureGuard } from './github-webhook-signature.guard';

@Controller('github/webhooks')
@UseGuards(GithubWebhookSignatureGuard)
export class GithubWebhookController {
  private readonly logger = new Logger(GithubWebhookController.name);

  constructor(
    private readonly producer: TeamGithubProducer,
    private readonly repoEventProducer: RepoEventProducer,
  ) {}

  @Post()
  @HttpCode(200)
  async handle(
    @Headers('x-github-event') eventName: string,
    @Body() payload: GithubWebhookPayload,
  ): Promise<{ ok: true }> {
    switch (eventName) {
      case 'installation':
        await this.handleInstallation(payload);
        break;
      case 'push':
      case 'issues':
      case 'pull_request':
        await this.handleRepoEvent(eventName, payload);
        break;
      default:
        this.logger.log(`Ignoring unhandled GitHub event: ${eventName}`);
    }
    return { ok: true };
  }

  private async handleInstallation(
    payload: GithubWebhookPayload,
  ): Promise<void> {
    if (payload?.action !== 'deleted') return; // 'created'는 state가 없어 상관관계를 지을 수 없음 — setup 콜백에서만 처리
    if (!payload.installation) return;
    await this.producer.send('team.github.disconnected', {
      installationId: payload.installation.id,
    });
  }

  private async handleRepoEvent(
    eventName: string,
    payload: GithubWebhookPayload,
  ): Promise<void> {
    const fullName = payload?.repository?.full_name;
    if (!fullName) return;

    const [owner, repo] = fullName.split('/');
    const action: string = payload.action ?? 'pushed';
    const summary = this.buildSummary(eventName, action, payload);

    await this.repoEventProducer.send({
      owner,
      repo,
      eventType: eventName,
      action,
      summary,
    });
  }

  private buildSummary(
    eventName: string,
    action: string,
    payload: GithubWebhookPayload,
  ): string {
    if (eventName === 'push') {
      const commitCount = payload.commits?.length ?? 0;
      const ref = (payload.ref ?? '').replace('refs/heads/', '');
      const pusher = payload.pusher?.name ?? 'unknown';
      return `📦 ${pusher}님이 ${ref}에 커밋 ${commitCount}개를 푸시했습니다.`;
    }
    if (eventName === 'issues') {
      return `📝 이슈 #${payload.issue?.number} ${action}: ${payload.issue?.title} (${payload.issue?.html_url})`;
    }
    if (eventName === 'pull_request') {
      return `🔀 PR #${payload.pull_request?.number} ${action}: ${payload.pull_request?.title} (${payload.pull_request?.html_url})`;
    }
    return `${eventName} 이벤트가 발생했습니다.`;
  }
}
