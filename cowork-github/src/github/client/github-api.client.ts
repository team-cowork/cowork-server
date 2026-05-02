import { HttpService } from '@nestjs/axios';
import { Injectable } from '@nestjs/common';
import { AxiosError } from 'axios';
import { firstValueFrom } from 'rxjs';
import { GITHUB_API, GITHUB_HEADERS } from '../constants';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';

export interface CreatedIssue {
  number: number;
  html_url: string;
}

export interface SearchedIssue {
  number: number;
  html_url: string;
  title: string;
  labels: { name: string }[];
}

export interface GithubLabel {
  name: string;
}

export interface CreateLabelPayload {
  name: string;
  color: string;
  description?: string;
}

@Injectable()
export class GithubApiClient {
  constructor(private readonly httpService: HttpService) {}

  async createIssue(token: string, dto: CreateIssueDto): Promise<CreatedIssue> {
    try {
      const { data } = await firstValueFrom(
        this.httpService.post<CreatedIssue>(
          `${GITHUB_API}/repos/${dto.owner}/${dto.repo}/issues`,
          {
            title: dto.title,
            body: dto.body,
            labels: dto.labels,
            assignees: dto.assignees,
          },
          { headers: this.authHeaders(token) },
        ),
      );
      return { number: data.number, html_url: data.html_url };
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async searchOpenIssuesByTitle(
    token: string,
    dto: CreateIssueDto,
  ): Promise<SearchedIssue[]> {
    try {
      const escapedTitle = dto.title
        .replace(/\\/g, '\\\\')
        .replace(/"/g, '\\"');
      const query = `repo:${dto.owner}/${dto.repo} is:issue is:open in:title "${escapedTitle}"`;
      const { data } = await firstValueFrom(
        this.httpService.get<{ items: SearchedIssue[] }>(
          `${GITHUB_API}/search/issues`,
          {
            params: { q: query },
            headers: this.authHeaders(token),
          },
        ),
      );
      return data.items.map((item) => ({
        number: item.number,
        html_url: item.html_url,
        title: item.title,
        labels: (item.labels ?? []).map((l) => ({ name: l.name })),
      }));
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async addLabelsToIssue(
    token: string,
    dto: CreateIssueDto,
    issueNumber: number,
    labels: string[],
  ): Promise<void> {
    try {
      await firstValueFrom(
        this.httpService.post(
          `${GITHUB_API}/repos/${dto.owner}/${dto.repo}/issues/${issueNumber}/labels`,
          { labels },
          { headers: this.authHeaders(token) },
        ),
      );
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async listLabels(token: string, dto: CreateIssueDto): Promise<string[]> {
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<GithubLabel[]>(
          `${GITHUB_API}/repos/${dto.owner}/${dto.repo}/labels`,
          { params: { per_page: 100 }, headers: this.authHeaders(token) },
        ),
      );
      return data.map((label) => label.name);
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async createLabel(
    token: string,
    dto: CreateIssueDto,
    label: CreateLabelPayload,
  ): Promise<void> {
    try {
      await firstValueFrom(
        this.httpService.post(
          `${GITHUB_API}/repos/${dto.owner}/${dto.repo}/labels`,
          label,
          { headers: this.authHeaders(token) },
        ),
      );
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  private authHeaders(token: string) {
    return { ...GITHUB_HEADERS, Authorization: `Bearer ${token}` };
  }

  private handleGithubError(error: unknown): never {
    if (
      error instanceof AxiosError &&
      error.response &&
      error.response.status < 500 &&
      error.response.status !== 429
    ) {
      throw new GithubClientError(
        (error.response.data as { message?: string })?.message ?? error.message,
        error.response.status,
      );
    }
    throw error;
  }
}
