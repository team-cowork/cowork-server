import { Injectable, Logger } from '@nestjs/common';
import {
  CreateLabelPayload,
  GithubApiClient,
} from '../client/github-api.client';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';
import {
  BUG_KEYWORDS,
  BUG_LABEL_CANDIDATES,
  COWORK_DEFAULT_LABELS,
  ENHANCEMENT_KEYWORDS,
  ENHANCEMENT_LABEL_CANDIDATES,
  FALLBACK_LABEL_CANDIDATES,
  QUESTION_KEYWORDS,
  QUESTION_LABEL_CANDIDATES,
} from './label.constants';

@Injectable()
export class LabelService {
  private readonly logger = new Logger(LabelService.name);

  constructor(private readonly apiClient: GithubApiClient) {}

  async ensureUsableLabels(
    token: string,
    dto: CreateIssueDto,
  ): Promise<string[]> {
    const repoLabels = await this.apiClient.listLabels(token, dto);
    const requestedLabels = dto.labels ?? [];

    const createdDefaults = await this.createMissingLabels(
      token,
      dto,
      COWORK_DEFAULT_LABELS,
      repoLabels,
    );

    const handledRequested = await this.createRequestedLabels(
      token,
      dto,
      requestedLabels,
      [...repoLabels, ...createdDefaults],
    );
    return this.uniqueLabels([
      ...repoLabels,
      ...createdDefaults,
      ...handledRequested,
    ]);
  }

  resolveLabels(dto: CreateIssueDto, repoLabels: string[]): string[] {
    if (dto.labels && dto.labels.length > 0) {
      const matched = dto.labels.filter((label) =>
        this.hasRepoLabel(repoLabels, label),
      );
      if (matched.length > 0) return matched;
    }

    const text = `${dto.title} ${dto.body ?? ''}`.toLowerCase();
    if (this.includesAny(text, BUG_KEYWORDS)) {
      return this.matchRepoLabels(repoLabels, BUG_LABEL_CANDIDATES);
    }
    if (this.includesAny(text, ENHANCEMENT_KEYWORDS)) {
      return this.matchRepoLabels(repoLabels, ENHANCEMENT_LABEL_CANDIDATES);
    }
    if (this.includesAny(text, QUESTION_KEYWORDS)) {
      return this.matchRepoLabels(repoLabels, QUESTION_LABEL_CANDIDATES);
    }
    return this.matchRepoLabels(repoLabels, FALLBACK_LABEL_CANDIDATES);
  }

  private async createRequestedLabels(
    token: string,
    dto: CreateIssueDto,
    requestedLabels: string[],
    existingLabels: string[],
  ): Promise<string[]> {
    const alreadyExisting = this.uniqueLabels(requestedLabels).filter((label) =>
      this.hasRepoLabel(existingLabels, label),
    );

    const labelsToCreate = this.uniqueLabels(requestedLabels)
      .filter((label) => label.trim().length > 0)
      .filter((label) => !this.hasRepoLabel(existingLabels, label))
      .map((label) => this.createCustomLabel(label));

    const newlyCreated = await this.createMissingLabels(
      token,
      dto,
      labelsToCreate,
      existingLabels,
    );
    return this.uniqueLabels([...alreadyExisting, ...newlyCreated]);
  }

  private async createMissingLabels(
    token: string,
    dto: CreateIssueDto,
    labels: CreateLabelPayload[],
    existingLabels: string[],
  ): Promise<string[]> {
    const created: string[] = [];
    for (const label of labels) {
      if (this.hasRepoLabel(existingLabels, label.name)) continue;

      try {
        await this.apiClient.createLabel(token, dto, label);
        this.logger.log('Repository label created', {
          owner: dto.owner,
          repo: dto.repo,
          label: label.name,
        });
        created.push(label.name);
      } catch (error) {
        if (
          error instanceof GithubClientError &&
          (error.statusCode === 422 || error.statusCode === 403)
        ) {
          this.logger.log(
            `Repository label creation skipped (status: ${error.statusCode})`,
            {
              owner: dto.owner,
              repo: dto.repo,
              label: label.name,
            },
          );
          continue;
        }
        throw error;
      }
    }
    return created;
  }

  private includesAny(text: string, keywords: readonly string[]): boolean {
    return keywords.some((keyword) => text.includes(keyword));
  }

  private matchRepoLabels(
    repoLabels: string[],
    candidates: readonly string[],
  ): string[] {
    const normalized = repoLabels.map((label) => ({
      label,
      lower: label.toLowerCase(),
    }));

    for (const candidate of candidates) {
      const lowerCandidate = candidate.toLowerCase();
      const exact = normalized.find(({ lower }) => lower === lowerCandidate);
      if (exact) return [exact.label];

      const prefix = normalized.find(({ lower }) =>
        lower.startsWith(`${lowerCandidate}:`),
      );
      if (prefix) return [prefix.label];
    }

    return [];
  }

  private hasRepoLabel(repoLabels: string[], label: string): boolean {
    const lower = label.toLowerCase();
    return repoLabels.some((l) => l.toLowerCase() === lower);
  }

  private uniqueLabels(labels: string[]): string[] {
    const seen = new Set<string>();
    const result: string[] = [];
    for (const label of labels) {
      const lower = label.toLowerCase();
      if (seen.has(lower)) continue;
      seen.add(lower);
      result.push(label);
    }
    return result;
  }

  private createCustomLabel(name: string): CreateLabelPayload {
    return {
      name,
      color: 'ededed',
      description: '사용자 요청으로 생성된 라벨',
    };
  }
}
