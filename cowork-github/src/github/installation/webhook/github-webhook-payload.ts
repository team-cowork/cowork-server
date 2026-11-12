import {type Nullable} from '../../common/nullable.js';

type WebhookRepository = {full_name: string; private?: boolean};

export type GithubWebhookPayload = {
	action?: string;
	installation?: {id: number; account?: {login?: string}};
	account?: {login?: string};
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
		user?: Nullable<{login?: string}>;
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
		body?: Nullable<string>;
		draft?: boolean;
		merged?: boolean;
		user?: Nullable<{login?: string}>;
		labels?: Array<{name: string}>;
		head?: {ref?: string};
		base?: {ref?: string};
		created_at?: string;
		updated_at?: string;
	};
	label?: {name?: string; color?: string};
	changes?: {
		name?: {from?: string};
		login?: {from?: string};
		base?: {ref?: {from?: string}};
		repository?: {name?: {from?: string}};
		owner?: {from?: {user?: {login?: string}; organization?: {login?: string}}};
	};
	comment?: {
		id?: number;
		body?: string;
		html_url?: string;
		user?: Nullable<{login?: string}>;
		performed_via_github_app?: Nullable<{id?: number}>;
		created_at?: string;
		updated_at?: string;
	};
};
