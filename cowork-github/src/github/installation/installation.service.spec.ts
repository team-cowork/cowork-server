import { Test, TestingModule } from '@nestjs/testing';
import { InstallationService } from './installation.service';
import { InstallationApiClient } from './client/installation-api.client';

describe('InstallationService', () => {
  let service: InstallationService;
  let apiClient: { listRepositories: jest.Mock };

  beforeEach(async () => {
    apiClient = { listRepositories: jest.fn() };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        InstallationService,
        { provide: InstallationApiClient, useValue: apiClient },
      ],
    }).compile();

    service = module.get<InstallationService>(InstallationService);
  });

  it('조직의 저장소 목록 조회를 InstallationApiClient에 위임한다', async () => {
    apiClient.listRepositories.mockResolvedValue([{ name: 'repo-a' }]);

    const result = await service.listOrgRepos('my-org');

    expect(result).toEqual([{ name: 'repo-a' }]);
    expect(apiClient.listRepositories).toHaveBeenCalledWith('my-org');
  });
});
