export interface TeamGithubConnectedEvent {
  state: string;
  installationId: number;
  orgLogin: string;
}

export interface TeamGithubDisconnectedEvent {
  installationId: number;
}
