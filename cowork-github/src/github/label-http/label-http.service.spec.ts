import { Test, TestingModule } from '@nestjs/testing';
import { LabelHttpApiClient } from './client/label-http-api.client';
import { LabelHttpService } from './label-http.service';

describe('LabelHttpService', () => {
  let service: LabelHttpService;
  let apiClient: { listLabels: jest.Mock };

  beforeEach(async () => {
    apiClient = { listLabels: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        LabelHttpService,
        { provide: LabelHttpApiClient, useValue: apiClient },
      ],
    }).compile();

    service = module.get<LabelHttpService>(LabelHttpService);
  });

  it('raw 라벨 목록을 name/color 응답 DTO로 매핑한다', async () => {
    apiClient.listLabels.mockResolvedValue([
      { name: 'bug', color: 'd73a4a', description: 'Something is not working' },
      { name: 'enhancement', color: 'a2eeef' },
    ]);

    const result = await service.listLabels('my-org', 'my-repo');

    expect(result).toEqual([
      { name: 'bug', color: 'd73a4a' },
      { name: 'enhancement', color: 'a2eeef' },
    ]);
    expect(apiClient.listLabels).toHaveBeenCalledWith('my-org', 'my-repo');
  });

  it('라벨이 없으면 빈 배열을 반환한다', async () => {
    apiClient.listLabels.mockResolvedValue([]);

    const result = await service.listLabels('my-org', 'my-repo');

    expect(result).toEqual([]);
  });
});
