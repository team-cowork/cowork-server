export type RepoStateEvent = {
    installationId: number;
    owner: string;
    repo: string;
    fullName: string;
    private: boolean;
    htmlUrl: string;
    deleted: boolean;
    sourceOccurredAt: string;
};
