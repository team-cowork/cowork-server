export const githubCacheKeys = {
  installation: (owner: string): string => `github:installation:${owner}`,
  token: (installationId: number): string => `github:token:${installationId}`,
};
