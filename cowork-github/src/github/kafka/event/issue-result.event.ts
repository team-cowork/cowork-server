export interface IssueResultEvent {
  channelId: number;
  teamId: number;
  success: boolean;
  issueUrl?: string;
  issueNumber?: number;
  error?: string;
}
