import {readCommentAuthor} from '../common/comment-author-marker.js';
import {type Nullable} from '../common/nullable.js';

export type TimelineEvent = {
	event: string;
	node_id?: string;
	id?: number;
	created_at?: string;
	actor?: Nullable<{login: string}>;
	user?: Nullable<{login: string}>;
	performed_via_github_app?: Nullable<{id: number}>;
	body?: string;
	html_url?: string;
	sha?: string;
	commit_id?: Nullable<string>;
	committer?: {date: string; name: string};
	author?: {date: string; name: string};
	message?: string;
	submitted_at?: string;
	state?: string;
	label?: {name: string; color: string};
	path?: string;
	line?: Nullable<number>;
	original_line?: Nullable<number>;
	pull_request_review_id?: number;
	dismissed_review?: {dismissal_message?: string};
};

export type TimelineActivity = {
	id: string;
	type: string;
	occurredAt: string;
	actor?: string;
	body?: string;
	htmlUrl?: string;
	commitSha?: string;
	reviewState?: string;
	labelName?: string;
	labelColor?: string;
	commentId?: number;
	reviewId?: number;
	path?: string;
	line?: number;
};

const supported = new Set(['commented', 'committed', 'referenced', 'reviewed', 'review_commented', 'review_dismissed', 'labeled', 'unlabeled', 'closed', 'reopened', 'merged']);

export function toTimelineActivity(event: TimelineEvent, appId: string): TimelineActivity | undefined {
	if (!supported.has(event.event) || (event.event === 'reviewed' && event.state?.toLowerCase() === 'pending')) {
		return undefined;
	}

	const type = event.event;
	const id = event.node_id ?? event.sha ?? event.id?.toString();
	const date = type === 'committed' ? event.committer?.date : (type === 'reviewed' ? event.submitted_at : event.created_at);
	if (id === undefined || date === undefined || !Number.isFinite(Date.parse(date))) {
		throw new Error(`Missing GitHub timeline identity or timestamp for ${type}`);
	}

	const activity: TimelineActivity = {
		id: `${type}:${id}`, type, occurredAt: new Date(date).toISOString(),
		actor: event.actor?.login, htmlUrl: event.html_url,
	};

	switch (type) {
		case 'commented': {
			const {author, body} = readCommentAuthor(event, appId);
			return {
				...activity, actor: author, body, commentId: event.id,
			};
		}

		case 'committed': {
			return {
				...activity, actor: event.author?.name ?? event.committer?.name, body: event.message, commitSha: event.sha,
			};
		}

		case 'review_commented': {
			return {
				...activity, actor: event.user?.login, body: event.body, commitSha: event.commit_id ?? undefined,
				commentId: event.id, reviewId: event.pull_request_review_id, path: event.path, line: event.line ?? event.original_line ?? undefined,
			};
		}

		case 'reviewed': {
			return {
				...activity, actor: event.user?.login, body: event.body, reviewId: event.id, reviewState: event.state, commitSha: event.commit_id ?? undefined,
			};
		}

		case 'review_dismissed': {
			return {...activity, body: event.dismissed_review?.dismissal_message};
		}

		case 'labeled':
		case 'unlabeled': {
			return {...activity, labelName: event.label?.name, labelColor: event.label?.color};
		}

		default: {
			return {...activity, commitSha: event.commit_id ?? undefined};
		}
	}
}
