/**
 * compacted state topic의 메시지 키는 영구 스키마다. compaction은 키별 최신 레코드를 무기한
 * 보존하므로, 은퇴한 키 포맷으로 쓰인 레코드는 만료되지 않고 earliest부터의 재생에 계속 나타난다.
 *
 * TODO(topic-versioning): 아래 은퇴 키 목록은 토픽 버전 분리 컷오버까지만 유지하는 임시 조치다.
 * `docs/todo/items/40-reliability/state-topic-key-versioning.md` 참고.
 * 새 토픽에는 현재 포맷 키만 쌓이므로, 컷오버와 재구축이 끝나면 이 파일 전체를 삭제하고
 * 각 consumer의 키 검사를 현재 포맷 단일 비교로 되돌린다.
 */

/** 현재 키이거나 은퇴한 키 중 하나와 일치하는지 판정한다. */
export function matchesEntityKey(
    messageKey: string | undefined,
    currentKey: string,
    ...retiredKeys: (string | null | undefined)[]
): boolean {
    if (messageKey === currentKey) return true;
    return retiredKeys.some((key) => key !== null && key !== undefined && messageKey === key);
}

/**
 * `channel.member.event`·`project.member.event`의 키.
 * 은퇴 포맷: `<parentId>` 단독 (channel `e03d5113`, project `7e7b203b` 이전).
 */
export function matchesCompositeEntityKey(
    messageKey: string | undefined,
    parentId: number,
    childId: number,
): boolean {
    return matchesEntityKey(messageKey, `${parentId}:${childId}`, `${parentId}`);
}

/**
 * `channel.event`의 키.
 * 은퇴 포맷: `<teamId>` (`9a51d22a`~), DM 채널의 `dm-<channelId>` (`8e0d97bb`~`e03d5113`).
 */
export function matchesChannelEventKey(
    messageKey: string | undefined,
    channelId: number,
    teamId: number | null,
): boolean {
    return matchesEntityKey(
        messageKey,
        `${channelId}`,
        teamId === null ? null : `${teamId}`,
        `dm-${channelId}`,
    );
}

/**
 * `project.event`의 키.
 * 은퇴 포맷: `<teamId>` (`7e7b203b` 이전).
 */
export function matchesProjectEventKey(
    messageKey: string | undefined,
    projectId: number,
    teamId: number,
): boolean {
    return matchesEntityKey(messageKey, `${projectId}`, `${teamId}`);
}
