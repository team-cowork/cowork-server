export const githubCacheKeys = {
    installation: (owner: string): string => `github:installation:${owner}`,
    token: (installationId: number): string => `github:token:${installationId}`,
    installationRevision: (installationId: number): string =>
        `github:installation:revision:${installationId}`,
    syncLock: (target: string): string => `github:sync:lock:${target}`,
    // 백필은 소문자로 정규화된 owner/repo로, webhook은 GitHub 원래 대소문자로 같은 키를 쓴다.
    pullRequestFiles: (owner: string, repo: string, number: number): string =>
        `github:pr-files:${owner.toLowerCase()}/${repo.toLowerCase()}#${number}`,
};
