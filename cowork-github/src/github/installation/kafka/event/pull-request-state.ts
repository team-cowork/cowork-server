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
     한 번이라도 조회된 적이 있으면(opened/synchronize/reopened/base 변경) 그 뒤로는 매 이벤트마다
     마지막으로 알려진 값을 채워 보낸다 — 이 토픽은 key로 compaction되므로, 파일이 없는 이벤트가
     살아남으면 과거에 조회했던 파일 목록이 영구히 사라질 수 있기 때문이다. 한 번도 조회된 적
     없으면(PR 생성 이후 synchronize가 아직 없는 경우 등) 생략된다.
     */
    files?: PullRequestFileState[];
};
