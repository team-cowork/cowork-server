import {toRepoIdentity} from './repo-identity';

/**
 동기화 완료 마커. 대상(repo 하나, 또는 org의 repo 목록)의 state 레코드를 모두 보낸 뒤 같은 state 토픽에
 마지막으로 보낸다. state 토픽은 파티션이 1개라(IaC에서 고정), cowork-project
 consumer는 앞선 state 레코드를 모두 반영한 뒤에야 이 마커를 처리한다. `repo`가 null이면 org 단위(repo 목록) 마커다.
 */
export type SyncCompletedMarker = {
    recordType: 'SYNC_COMPLETED';
    owner: string;
    repo: string | null;
    completedAt: string;
};

export const syncCompletedMarker = (owner: string, repo: string | null): SyncCompletedMarker => ({
    recordType: 'SYNC_COMPLETED',
    owner: toRepoIdentity(owner),
    repo: repo === null ? null : toRepoIdentity(repo),
    completedAt: new Date().toISOString(),
});

export const syncCompletedKey = (marker: SyncCompletedMarker): string =>
    `sync:${marker.repo === null ? marker.owner : `${marker.owner}/${marker.repo}`}`;
