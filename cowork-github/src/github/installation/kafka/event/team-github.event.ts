export type TeamGithubConnectedEvent = {
    state: string;
    installationId: number;
    orgLogin: string;
    revision: number;
};

export type TeamGithubDisconnectedEvent = {
    installationId: number;
    revision: number;
};
