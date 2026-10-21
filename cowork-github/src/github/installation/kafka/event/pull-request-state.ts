export type PullRequestFileState = {
    filename: string;
    status: string;
    additions: number;
    deletions: number;
    patch: string | null;
};

export type PullRequestStateEvent = {
    owner: string;
    repo: string;
    number: number;
    title: string;
    body: string | null;
    author: string;
    state: string;
    draft: boolean;
    merged: boolean;
    htmlUrl: string;
    labels: string[];
    headRef: string;
    baseRef: string;
    createdAt: string;
    updatedAt: string;
    sourceOccurredAt: string;
    /**
     Opened/synchronize/reopened 액션에서만 채워진다. 다른 액션(labeled, closed 등)에서는
     파일 목록이 바뀌지 않았으므로 생략되며, consumer는 이 필드가 없으면 기존 값을 유지한다.
     */
    files?: PullRequestFileState[];
};
