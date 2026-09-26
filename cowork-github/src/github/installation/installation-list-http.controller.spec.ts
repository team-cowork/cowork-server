import { Test, TestingModule } from '@nestjs/testing';
import { HttpException } from '@nestjs/common';
import { InstallationListHttpController } from './installation-list-http.controller';
import { InstallationService } from './installation.service';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';

describe('InstallationListHttpController', () => {
  let controller: InstallationListHttpController;
  let installationService: { listOrgRepos: jest.Mock };

  beforeEach(async () => {
    installationService = { listOrgRepos: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [InstallationListHttpController],
      providers: [
        { provide: InstallationService, useValue: installationService },
      ],
    })
      .overrideGuard(InternalApiKeyGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<InstallationListHttpController>(
      InstallationListHttpController,
    );
  });

  it('조직의 저장소 목록을 조회한다', async () => {
    installationService.listOrgRepos.mockResolvedValue([{ name: 'repo-a' }]);

    const result = await controller.list('my-org');

    expect(result).toEqual([{ name: 'repo-a' }]);
    expect(installationService.listOrgRepos).toHaveBeenCalledWith('my-org');
  });

  it('GithubClientError는 동일한 statusCode의 HttpException으로 변환한다', async () => {
    installationService.listOrgRepos.mockRejectedValue(
      new GithubClientError('조직을 찾을 수 없습니다.', 404),
    );

    await expect(controller.list('my-org')).rejects.toMatchObject({
      response: '조직을 찾을 수 없습니다.',
      status: 404,
    });
  });

  it('알 수 없는 에러는 502로 변환한다', async () => {
    installationService.listOrgRepos.mockRejectedValue(
      new Error('network down'),
    );

    let caught: unknown;
    try {
      await controller.list('my-org');
    } catch (e) {
      caught = e;
    }

    expect(caught).toBeInstanceOf(HttpException);
    expect((caught as HttpException).getStatus()).toBe(502);
  });
});
