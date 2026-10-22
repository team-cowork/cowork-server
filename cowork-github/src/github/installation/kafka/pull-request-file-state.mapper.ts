import {type PullRequestFile} from '../../pull-request/client/pull-request-api.client';
import {type PullRequestFileState} from './event/pull-request-state';

export function toPullRequestFileStates(files: PullRequestFile[]): PullRequestFileState[] {
    return files.map(file => ({
        filename: file.filename,
        status: file.status,
        additions: file.additions,
        deletions: file.deletions,
        patch: file.patch ?? null,
    }));
}
