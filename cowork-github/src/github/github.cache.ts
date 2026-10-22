export const githubCacheKeys = {
    installation: (owner: string): string => `github:installation:${owner}`,
    token: (installationId: number): string => `github:token:${installationId}`,
    installationRevision: (installationId: number): string =>
        `github:installation:revision:${installationId}`,
    pullRequestFiles: (owner: string, repo: string, number: number): string =>
        `github:pr-files:${owner}/${repo}#${number}`,
    syncLock: (target: string): string => `github:sync:lock:${target}`,
};
