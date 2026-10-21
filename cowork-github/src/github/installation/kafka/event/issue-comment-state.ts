export type IssueCommentStateEvent = {
    owner: string;
    repo: string;
    commentId: number;
    parentType: 'ISSUE' | 'PULL_REQUEST';
    parentNumber: number;
    author: string;
    body: string;
    htmlUrl: string;
    createdAt: string;
    updatedAt: string;
    deleted: boolean;
    sourceOccurredAt: string;
};
