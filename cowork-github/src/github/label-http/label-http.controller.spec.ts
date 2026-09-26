import { Test, TestingModule } from '@nestjs/testing';
import { HttpException } from '@nestjs/common';
import { LabelHttpController } from './label-http.controller';
import { LabelHttpService } from './label-http.service';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';

describe('LabelHttpController', () => {
  let controller: LabelHttpController;
  let labelHttpService: { listLabels: jest.Mock };

  beforeEach(async () => {
    labelHttpService = { listLabels: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      controllers: [LabelHttpController],
      providers: [{ provide: LabelHttpService, useValue: labelHttpService }],
    })
      .overrideGuard(InternalApiKeyGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<LabelHttpController>(LabelHttpController);
  });

  it('라벨 목록을 조회한다', async () => {
    labelHttpService.listLabels.mockResolvedValue([
      { name: 'bug', color: 'd73a4a' },
    ]);

    const result = await controller.list('my-org', 'my-repo');

    expect(result).toEqual([{ name: 'bug', color: 'd73a4a' }]);
    expect(labelHttpService.listLabels).toHaveBeenCalledWith(
      'my-org',
      'my-repo',
    );
  });

  it('GithubClientError는 동일한 statusCode의 HttpException으로 변환한다', async () => {
    labelHttpService.listLabels.mockRejectedValue(
      new GithubClientError('저장소를 찾을 수 없습니다.', 404),
    );

    await expect(controller.list('my-org', 'my-repo')).rejects.toMatchObject({
      response: '저장소를 찾을 수 없습니다.',
      status: 404,
    });
  });

  it('알 수 없는 에러는 502로 변환한다', async () => {
    labelHttpService.listLabels.mockRejectedValue(new Error('network down'));

    let caught: unknown;
    try {
      await controller.list('my-org', 'my-repo');
    } catch (e) {
      caught = e;
    }

    expect(caught).toBeInstanceOf(HttpException);
    expect((caught as HttpException).getStatus()).toBe(502);
  });
});
