export const REDIS_CLIENT = 'REDIS_CLIENT';

export const GITHUB_API = 'https://api.github.com';
export const GITHUB_HEADERS = {
  Accept: 'application/vnd.github+json',
  'X-GitHub-Api-Version': '2022-11-28',
} as const;

export const DEFAULT_GITHUB_TOKEN_CACHE_TTL_SECONDS = 3300;
export const DEFAULT_GITHUB_INSTALLATION_CACHE_TTL_SECONDS = 86400;
export const DEFAULT_GITHUB_ISSUE_MAX_RETRIES = 3;
