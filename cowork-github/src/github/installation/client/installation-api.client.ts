import { HttpService } from '@nestjs/axios';
import { Injectable } from '@nestjs/common';
import { AxiosError } from 'axios';
import { firstValueFrom } from 'rxjs';
import { GithubAuthService } from '../../auth/github-auth.service';
import { GITHUB_API, GITHUB_HEADERS } from '../../constants';
import { GithubClientError } from '../../github.errors';

export interface GithubRepoSummary {
  name: string;
  fullName: string;
  private: boolean;
  htmlUrl: string;
}

interface GithubRepo {
  name: string;
  full_name: string;
  private: boolean;
  html_url: string;
}

interface ListInstallationRepositoriesResponse {
  total_count: number;
  repositories: GithubRepo[];
}

@Injectable()
export class InstallationApiClient {
  constructor(
    private readonly httpService: HttpService,
    private readonly authService: GithubAuthService,
  ) {}

  async listRepositories(owner: string): Promise<GithubRepoSummary[]> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<ListInstallationRepositoriesResponse>(
          `${GITHUB_API}/installation/repositories`,
          {
            params: { per_page: 100 },
            headers: this.authHeaders(token),
          },
        ),
      );
      return data.repositories.map((repo) => ({
        name: repo.name,
        fullName: repo.full_name,
        private: repo.private,
        htmlUrl: repo.html_url,
      }));
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
