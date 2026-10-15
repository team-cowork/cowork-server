import { Injectable } from '@nestjs/common';
import {
  GithubRepoSummary,
  InstallationApiClient,
} from './client/installation-api.client';

@Injectable()
export class InstallationService {
  constructor(private readonly apiClient: InstallationApiClient) {}

  async listOrgRepos(org: string): Promise<GithubRepoSummary[]> {
    return this.apiClient.listRepositories(org);
  }
}
