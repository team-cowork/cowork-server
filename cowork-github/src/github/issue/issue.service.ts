import { Injectable, Logger } from '@nestjs/common';
import { AppConfigService } from '../../config/app-config.service';
import {
  CreateLabelPayload,
  GithubApiClient,
} from '../client/github-api.client';
import { GithubAuthService } from '../auth/github-auth.service';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';

@Injectable()
export class IssueService {
  private readonly logger = new Logger(IssueService.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly authService: GithubAuthService,
    private readonly apiClient: GithubApiClient,
  ) {}

  async createIssue(dto: CreateIssueDto): Promise<void> {
    const maxRetries = this.config.githubIssueMaxRetries;

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        const token = await this.authService.getInstallationToken(dto.owner);
        const repoLabels = await this.ensureUsableLabels(token, dto);
        this.logger.log('Repository labels loaded', {
          owner: dto.owner,
          repo: dto.repo,
          labelCount: repoLabels.length,
        });

        const labels = this.resolveLabels(dto, repoLabels);
        this.logger.log('Issue labels resolved', {
          owner: dto.owner,
          repo: dto.repo,
          title: dto.title,
          labels,
        });

        const issueDto = {
          ...dto,
          labels: labels.length > 0 ? labels : undefined,
        };
        const existingIssues = await this.apiClient.searchOpenIssuesByTitle(
          token,
          dto,
        );
        const existingIssue = existingIssues[0];

        if (existingIssue) {
          if (labels.length > 0) {
            await this.apiClient.addLabelsToIssue(
              token,
              dto,
              existingIssue.number,
              labels,
            );
            this.logger.log('Labels added to existing issue', {
              owner: dto.owner,
              repo: dto.repo,
              issueNumber: existingIssue.number,
              labels,
            });
          }
          this.logger.log(`Existing issue found: ${existingIssue.html_url}`);
          return;
        }

        const result = await this.apiClient.createIssue(token, issueDto);
        this.logger.log('Issue created', {
          owner: dto.owner,
          repo: dto.repo,
          issueNumber: result.number,
          issueUrl: result.html_url,
          labels,
        });
        return;
      } catch (error) {
        if (error instanceof GithubClientError) throw error;

        this.logger.error('GitHub API error', {
          owner: dto.owner,
          repo: dto.repo,
          title: dto.title,
          attempt,
          message: (error as Error).message,
        });

        if (attempt === maxRetries) throw error;
        await this.sleep(Math.pow(2, attempt - 1) * 1000);
      }
    }
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  private async ensureUsableLabels(
    token: string,
    dto: CreateIssueDto,
  ): Promise<string[]> {
    const repoLabels = await this.apiClient.listLabels(token, dto);
    const requestedLabels = dto.labels ?? [];

    if (repoLabels.length === 0) {
      await this.createMissingLabels(token, dto, COWORK_DEFAULT_LABELS, []);
      const defaultLabelNames = COWORK_DEFAULT_LABELS.map(
        (label) => label.name,
      );
      await this.createRequestedLabels(
        token,
        dto,
        requestedLabels,
        defaultLabelNames,
      );
      return this.uniqueLabels([...defaultLabelNames, ...requestedLabels]);
    }

    await this.createRequestedLabels(token, dto, requestedLabels, repoLabels);
    return this.uniqueLabels([...repoLabels, ...requestedLabels]);
  }

  private async createRequestedLabels(
    token: string,
    dto: CreateIssueDto,
    requestedLabels: string[],
    existingLabels: string[],
  ): Promise<void> {
    const labelsToCreate = this.uniqueLabels(requestedLabels)
      .filter((label) => label.trim().length > 0)
      .filter((label) => !this.hasRepoLabel(existingLabels, label))
      .map((label) => this.createCustomLabel(label));

    await this.createMissingLabels(token, dto, labelsToCreate, existingLabels);
  }

  private async createMissingLabels(
    token: string,
    dto: CreateIssueDto,
    labels: CreateLabelPayload[],
    existingLabels: string[],
  ): Promise<void> {
    for (const label of labels) {
      if (this.hasRepoLabel(existingLabels, label.name)) continue;

      try {
        await this.apiClient.createLabel(token, dto, label);
        this.logger.log('Repository label created', {
          owner: dto.owner,
          repo: dto.repo,
          label: label.name,
        });
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
  }

  private resolveLabels(dto: CreateIssueDto, repoLabels: string[]): string[] {
    if (dto.labels && dto.labels.length > 0) {
      const requestedLabels = dto.labels.filter((label) =>
        this.hasRepoLabel(repoLabels, label),
      );
      if (requestedLabels.length > 0) return requestedLabels;
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

  private includesAny(text: string, keywords: readonly string[]): boolean {
    return keywords.some((keyword) => text.includes(keyword));
  }

  private matchRepoLabels(
    repoLabels: string[],
    candidates: readonly string[],
  ): string[] {
    const normalizedRepoLabels = repoLabels.map((label) => ({
      label,
      normalized: label.toLowerCase(),
    }));

    for (const candidate of candidates) {
      const normalizedCandidate = candidate.toLowerCase();
      const exactMatch = normalizedRepoLabels.find(
        ({ normalized }) => normalized === normalizedCandidate,
      );
      if (exactMatch) return [exactMatch.label];

      const prefixMatch = normalizedRepoLabels.find(({ normalized }) =>
        normalized.startsWith(`${normalizedCandidate}:`),
      );
      if (prefixMatch) return [prefixMatch.label];
    }

    return [];
  }

  private hasRepoLabel(repoLabels: string[], label: string): boolean {
    const normalizedLabel = label.toLowerCase();
    return repoLabels.some(
      (repoLabel) => repoLabel.toLowerCase() === normalizedLabel,
    );
  }

  private uniqueLabels(labels: string[]): string[] {
    const seen = new Set<string>();
    const unique: string[] = [];

    for (const label of labels) {
      const normalized = label.toLowerCase();
      if (seen.has(normalized)) continue;
      seen.add(normalized);
      unique.push(label);
    }

    return unique;
  }

  private createCustomLabel(name: string): CreateLabelPayload {
    return {
      name,
      color: 'ededed',
      description: '사용자 요청으로 생성된 라벨',
    };
  }
}

const COWORK_DEFAULT_LABELS: CreateLabelPayload[] = [
  {
    name: 'blocked:차단됨',
    color: 'b60205',
    description: '진행이 차단된 이슈',
  },
  { name: 'bug:버그', color: 'd73a4a', description: '버그 또는 오작동' },
  {
    name: 'documentation:문서화',
    color: '0075ca',
    description: '문서 작성 또는 수정',
  },
  { name: 'duplicate:중복', color: 'cfd3d7', description: '중복 이슈' },
  {
    name: 'enhancement:개선작업',
    color: 'a2eeef',
    description: '기능 추가 또는 개선',
  },
  {
    name: 'GFI:첫 기여 추천',
    color: '7057ff',
    description: '첫 기여자에게 추천할 만한 작업',
  },
  {
    name: 'help wanted:도움 필요',
    color: '008672',
    description: '도움이나 추가 논의가 필요한 이슈',
  },
  {
    name: 'invalid:무효한',
    color: 'e4e669',
    description: '유효하지 않은 이슈',
  },
  { name: 'release:릴리즈', color: '5319e7', description: '릴리즈 관련 작업' },
  {
    name: 'waiting for review:검토 대기',
    color: 'fbca04',
    description: '검토 대기 상태',
  },
];

const BUG_KEYWORDS = [
  'error',
  'bug',
  'fail',
  'exception',
  'crash',
  '500',
  '오류',
  '에러',
  '실패',
  '안됨',
  '안돼',
  '안되',
  '이상',
  '문제',
  '오작동',
  '먹통',
] as const;

const ENHANCEMENT_KEYWORDS = [
  'feature',
  'enhancement',
  'support',
  '추가',
  '개선',
  '요청',
  '기능',
] as const;

const QUESTION_KEYWORDS = [
  'question',
  'how',
  '문의',
  '질문',
  '어떻게',
  '가능',
] as const;

const BUG_LABEL_CANDIDATES = ['bug:버그', 'bug'] as const;
const ENHANCEMENT_LABEL_CANDIDATES = [
  'enhancement',
  'enhancement:개선작업',
] as const;
const QUESTION_LABEL_CANDIDATES = [
  'question',
  'help wanted:도움 필요',
] as const;
const FALLBACK_LABEL_CANDIDATES = [
  'help wanted:도움 필요',
  'waiting for review:검토 대기',
  'question',
  'enhancement:개선작업',
  'bug:버그',
] as const;
