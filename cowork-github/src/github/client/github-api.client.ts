import { HttpService } from '@nestjs/axios';
import { Injectable } from '@nestjs/common';
import { AxiosError } from 'axios';
import { firstValueFrom } from 'rxjs';
import { GITHUB_HEADERS } from '../constants';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';

export interface CreatedIssue {
  number: number;
  html_url: string;
}

@Injectable()
export class GithubApiClient {
  constructor(private readonly httpService: HttpService) {}

  async createIssue(token: string, dto: CreateIssueDto): Promise<CreatedIssue> {
    try {
      const { data } = await firstValueFrom(
        this.httpService.post<CreatedIssue>(
          `https://api.github.com/repos/${dto.owner}/${dto.repo}/issues`,
          {
            title: dto.title,
            body: dto.body,
            labels: dto.labels,
            assignees: dto.assignees,
          },
          {
            headers: {
              ...GITHUB_HEADERS,
              Authorization: `Bearer ${token}`,
            },
          },
        ),
      );

      return { number: data.number, html_url: data.html_url };
    } catch (error) {
      if (
        error instanceof AxiosError &&
        error.response &&
        error.response.status < 500
      ) {
        throw new GithubClientError(
          (error.response.data as { message?: string })?.message ??
            error.message,
          error.response.status,
        );
      }

      throw error;
    }
  }
}
