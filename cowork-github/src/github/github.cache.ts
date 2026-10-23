// PR 파일 목록은 patch 본문까지 담아 크므로 만료시킨다. 만료 뒤의 webhook은 파일 목록을 다시 조회해 채운다.
export const PULL_REQUEST_FILES_TTL_SECONDS = 7 * 24 * 60 * 60;

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
