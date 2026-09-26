import { HttpService } from '@nestjs/axios';
import { Injectable } from '@nestjs/common';
import { AxiosError } from 'axios';
import { firstValueFrom } from 'rxjs';
import { GithubAuthService } from '../../auth/github-auth.service';
import { GITHUB_API, GITHUB_HEADERS } from '../../constants';
import { GithubClientError } from '../../github.errors';

export interface GithubRepoLabel {
  name: string;
  color: string;
}

@Injectable()
export class LabelHttpApiClient {
  constructor(
    private readonly httpService: HttpService,
    private readonly authService: GithubAuthService,
  ) {}

  // MVP: per_page=100 단일 페이지만 조회. 100개 초과 라벨 페이지네이션은 후속 과제.
  async listLabels(owner: string, repo: string): Promise<GithubRepoLabel[]> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<GithubRepoLabel[]>(
          `${GITHUB_API}/repos/${owner}/${repo}/labels`,
          { params: { per_page: 100 }, headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  // GitHub의 "Set labels for an issue" 엔드포인트. 기존 라벨 전체를 요청된 목록으로
  // 대체(replace)한다. listLabels(POST /labels 추가)와 달리 additive가 아니다.
  async replaceLabels(
    owner: string,
    repo: string,
    issueNumber: number,
    labels: string[],
  ): Promise<GithubRepoLabel[]> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.put<GithubRepoLabel[]>(
          `${GITHUB_API}/repos/${owner}/${repo}/issues/${issueNumber}/labels`,
          { labels },
          { headers: this.authHeaders(token) },
        ),
      );
      return data;
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
