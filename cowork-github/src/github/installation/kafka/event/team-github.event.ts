export interface TeamGithubConnectedEvent {
  state: string;
  installationId: number;
  orgLogin: string;
  revision: number;
}

export interface TeamGithubDisconnectedEvent {
  installationId: number;
  revision: number;
}
