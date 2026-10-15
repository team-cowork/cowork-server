export interface PullRequestResultEvent {
  channelId: number;
  teamId: number;
  success: boolean;
  prNumber: number;
  prUrl?: string;
  error?: string;
}
