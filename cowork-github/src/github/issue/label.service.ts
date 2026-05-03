import { Injectable, Logger } from '@nestjs/common';
import {
  CreateLabelPayload,
  GithubApiClient,
} from '../client/github-api.client';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';
import {
  BUG_KEYWORDS,
  BUG_LABEL,
  COWORK_FALLBACK_LABEL,
  COWORK_LABELS,
  ENHANCEMENT_KEYWORDS,
  ENHANCEMENT_LABEL,
  QUESTION_KEYWORDS,
  QUESTION_LABEL,
} from './label.constants';

const COWORK_LABEL_NAMES = new Set(COWORK_LABELS.map((l) => l.name.toLowerCase()));

@Injectable()
export class LabelService {
  private readonly logger = new Logger(LabelService.name);

  constructor(private readonly apiClient: GithubApiClient) {}

  resolveLabels(dto: CreateIssueDto): string[] {
    const explicit = dto.labels
      ? this.uniqueLabels(dto.labels.filter((l) => l.trim().length > 0))
      : [];

    const text = `${dto.title} ${dto.body ?? ''}`.toLowerCase();
    const keywordLabel = this.detectCoworkLabelName(text);

    const merged = this.uniqueLabels([
      ...explicit,
      ...(keywordLabel ? [keywordLabel] : []),
    ]);

    const hasCowork = merged.some((l) => COWORK_LABEL_NAMES.has(l.toLowerCase()));
    if (!hasCowork) {
      merged.push(COWORK_FALLBACK_LABEL);
    }

    return merged;
  }

  async ensureLabelsExist(
    token: string,
    dto: CreateIssueDto,
    labels: string[],
  ): Promise<void> {
    if (labels.length === 0) return;

    const repoLabels = await this.apiClient.listLabels(token, dto);

    for (const name of labels) {
      if (this.hasRepoLabel(repoLabels, name)) continue;

      const coworkDef = COWORK_LABELS.find(
        (l) => l.name.toLowerCase() === name.toLowerCase(),
      );
      const payload = coworkDef ?? this.createCustomLabel(name);

      try {
        await this.apiClient.createLabel(token, dto, payload);
        this.logger.log('Repository label created', {
          owner: dto.owner,
          repo: dto.repo,
          label: name,
        });
      } catch (error) {
        if (
          error instanceof GithubClientError &&
          (error.statusCode === 422 || error.statusCode === 403)
        ) {
          this.logger.log(
            `Repository label creation skipped (status: ${error.statusCode})`,
            { owner: dto.owner, repo: dto.repo, label: name },
          );
          continue;
        }
        throw error;
      }
    }
  }

  private detectCoworkLabelName(text: string): string | null {
    if (this.includesAny(text, BUG_KEYWORDS)) return BUG_LABEL;
    if (this.includesAny(text, ENHANCEMENT_KEYWORDS)) return ENHANCEMENT_LABEL;
    if (this.includesAny(text, QUESTION_KEYWORDS)) return QUESTION_LABEL;
    return null;
  }

  private includesAny(text: string, keywords: readonly string[]): boolean {
    return keywords.some((keyword) => text.includes(keyword));
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
