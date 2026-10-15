import { Injectable } from '@nestjs/common';
import { LabelHttpApiClient } from './client/label-http-api.client';

export interface LabelResponse {
  name: string;
  color: string;
}

@Injectable()
export class LabelHttpService {
  constructor(private readonly apiClient: LabelHttpApiClient) {}

  async listLabels(owner: string, repo: string): Promise<LabelResponse[]> {
    const labels = await this.apiClient.listLabels(owner, repo);
    return labels.map((label) => ({
      name: label.name,
      color: label.color,
    }));
  }

  async replaceLabels(
    owner: string,
    repo: string,
    issueNumber: number,
    labels: string[],
  ): Promise<LabelResponse[]> {
    const result = await this.apiClient.replaceLabels(
      owner,
      repo,
      issueNumber,
      labels,
    );
    return result.map((label) => ({
      name: label.name,
      color: label.color,
    }));
  }
}
