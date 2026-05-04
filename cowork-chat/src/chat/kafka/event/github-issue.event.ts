export interface GithubIssueCreateEvent {
    channelId: number;
    teamId: number;
    projectId: number;
    owner: string;
    repo: string;
    title: string;
    body?: string;
    requesterId: number;
}

export interface GithubIssueResultEvent {
    channelId: number;
    teamId: number;
    projectId?: number;
    success: boolean;
    issueUrl?: string;
    issueNumber?: number;
    error?: string;
}
