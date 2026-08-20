import { Controller, Get, Header, Query } from '@nestjs/common';
import { GithubAuthService } from '../../auth/github-auth.service';
import { TeamGithubProducer } from '../kafka/team-github.producer';

@Controller('github/setup')
export class GithubSetupController {
  constructor(
    private readonly authService: GithubAuthService,
    private readonly producer: TeamGithubProducer,
  ) {}

  @Get()
  @Header('Content-Type', 'text/html; charset=utf-8')
  async handle(
    @Query('installation_id') installationIdRaw: string,
    @Query('setup_action') setupAction: string,
    @Query('state') state?: string,
  ): Promise<string> {
    if (!installationIdRaw) {
      return this.page('설치 정보를 확인할 수 없습니다. 다시 시도해주세요.');
    }

    const installationId = Number(installationIdRaw);
    if (setupAction === 'delete') {
      return this.page('GitHub 앱 연동이 해제되었습니다. 이 창을 닫아주세요.');
    }

    try {
      const orgLogin =
        await this.authService.getInstallationAccountLogin(installationId);
      if (state) {
        await this.producer.send('team.github.connected', {
          state,
          installationId,
          orgLogin,
        });
      }
      return this.page(
        'GitHub 연동이 완료되었습니다. 이 창을 닫고 cowork로 돌아가세요.',
      );
    } catch {
      return this.page(
        'GitHub 연동 처리 중 오류가 발생했습니다. cowork에서 다시 시도해주세요.',
      );
    }
  }

  private page(message: string): string {
    return `<html><body style="font-family:sans-serif;text-align:center;padding-top:4rem;"><p>${message}</p></body></html>`;
  }
}
