export interface GithubIssueCreateEvent {
    channelId: number;
    teamId: number;
    owner: string;
    repo: string;
    title: string;
    body?: string;
    requesterId: number;
}

export interface GithubIssueResultEvent {
    channelId: number;
    teamId: number;
    success: boolean;
    issueUrl?: string;
    issueNumber?: number;
    error?: string;
}
