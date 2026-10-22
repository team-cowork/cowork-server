type WebhookRepository = {full_name: string; private?: boolean};

export type GithubWebhookPayload = {
    action?: string;
    installation?: {id: number};
    repository?: WebhookRepository;
    repositories_added?: WebhookRepository[];
    repositories_removed?: WebhookRepository[];
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
    changes?: {
        name?: {from?: string};
        base?: {ref?: {from?: string}};
        repository?: {name?: {from?: string}};
        owner?: {from?: {user?: {login?: string}; organization?: {login?: string}}};
    };
    comment?: {
        id?: number;
        body?: string;
        html_url?: string;
        user?: {login?: string} | null;
        created_at?: string;
        updated_at?: string;
    };
};
