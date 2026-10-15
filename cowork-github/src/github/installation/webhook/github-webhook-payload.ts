export interface GithubWebhookPayload {
  action?: string;
  installation?: { id: number };
  repository?: { full_name: string };
  commits?: unknown[];
  ref?: string;
  pusher?: { name?: string };
  issue?: { number?: number; title?: string; html_url?: string };
  pull_request?: { number?: number; title?: string; html_url?: string };
}
