import { Test, TestingModule } from '@nestjs/testing';
import { GithubSetupController } from './github-setup.controller';
import { GithubAuthService } from '../../auth/github-auth.service';
import { TeamGithubProducer } from '../kafka/team-github.producer';

describe('GithubSetupController', () => {
  let controller: GithubSetupController;
  let authService: { getInstallationAccountLogin: jest.Mock };
  let producer: { send: jest.Mock };

  beforeEach(async () => {
    authService = { getInstallationAccountLogin: jest.fn() };
    producer = { send: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [GithubSetupController],
      providers: [
        { provide: GithubAuthService, useValue: authService },
        { provide: TeamGithubProducer, useValue: producer },
      ],
    }).compile();

    controller = module.get<GithubSetupController>(GithubSetupController);
  });

  it('installation_id가 없으면 에러 안내 페이지를 반환한다', async () => {
    const result = await controller.handle('', 'install');

    expect(result).toContain('설치 정보를 확인할 수 없습니다');
    expect(authService.getInstallationAccountLogin).not.toHaveBeenCalled();
  });

  it('setup_action이 delete면 해제 안내 페이지를 반환하고 이벤트를 발행하지 않는다', async () => {
    const result = await controller.handle('42', 'delete');

    expect(result).toContain('연동이 해제되었습니다');
    expect(producer.send).not.toHaveBeenCalled();
  });

  it('state가 있으면 team.github.connected 이벤트를 발행한다', async () => {
    authService.getInstallationAccountLogin.mockResolvedValue('my-org');

    const result = await controller.handle('42', 'install', 'team-state');

    expect(result).toContain('연동이 완료되었습니다');
    expect(producer.send).toHaveBeenCalledWith('team.github.connected', {
      state: 'team-state',
      installationId: 42,
      orgLogin: 'my-org',
    });
  });

  it('state가 없으면 이벤트를 발행하지 않고 연결 실패 안내 페이지를 반환한다', async () => {
    authService.getInstallationAccountLogin.mockResolvedValue('my-org');

    const result = await controller.handle('42', 'install');

    expect(producer.send).not.toHaveBeenCalled();
    expect(result).toContain('cowork 팀과 연결하지 못했습니다');
  });

  it('계정 조회 실패 시 에러 안내 페이지를 반환한다', async () => {
    authService.getInstallationAccountLogin.mockRejectedValue(
      new Error('boom'),
    );

    const result = await controller.handle('42', 'install', 'team-state');

    expect(result).toContain('오류가 발생했습니다');
  });
});
