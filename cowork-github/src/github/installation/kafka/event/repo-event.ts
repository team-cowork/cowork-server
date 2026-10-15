export interface RepoEvent {
  owner: string;
  repo: string;
  eventType: string;
  action: string;
  summary: string;
}
