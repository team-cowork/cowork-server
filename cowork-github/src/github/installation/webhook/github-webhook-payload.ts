export type GithubWebhookPayload = {
    action?: string;
    installation?: {id: number};
    repository?: {full_name: string};
    commits?: unknown[];
    ref?: string;
    pusher?: {name?: string};
    issue?: {
        number?: number;
        title?: string;
        html_url?: string;
        state?: string;
        user?: {login?: string} | null;
        labels?: Array<{name: string}>;
        created_at?: string;
        updated_at?: string;
        pull_request?: unknown;
    };
    pull_request?: {
        number?: number;
        title?: string;
        html_url?: string;
        state?: string;
        body?: string | null;
        draft?: boolean;
        merged?: boolean;
        user?: {login?: string} | null;
        labels?: Array<{name: string}>;
        head?: {ref?: string};
        base?: {ref?: string};
        created_at?: string;
        updated_at?: string;
    };
    label?: {name?: string; color?: string};
    comment?: {
        id?: number;
        body?: string;
        html_url?: string;
        user?: {login?: string} | null;
        created_at?: string;
        updated_at?: string;
    };
};
