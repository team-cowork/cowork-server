export type IssueStateEvent = {
    owner: string;
    repo: string;
    number: number;
    title: string;
    author: string;
    state: string;
    htmlUrl: string;
    labels: string[];
    createdAt: string;
    updatedAt: string;
    deleted: boolean;
    sourceOccurredAt: string;
};
