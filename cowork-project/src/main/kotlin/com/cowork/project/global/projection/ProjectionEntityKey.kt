package com.cowork.project.global.projection

/**
 * compacted state topic의 메시지 키는 영구 스키마다. compaction은 키별 최신 레코드를 무기한
 * 보존하므로, 은퇴한 키 포맷으로 쓰인 레코드는 만료되지 않고 earliest부터의 재생에 계속 나타난다.
 *
 * TODO(topic-versioning): 은퇴 키 수용은 토픽 버전 분리 컷오버까지만 유지하는 임시 조치다.
 * `docs/todo/items/40-reliability/state-topic-key-versioning.md` 참고.
 * 새 토픽에는 현재 포맷 키만 쌓이므로, 컷오버와 재구축이 끝나면 이 파일을 삭제하고
 * `ChannelStateConsumer`의 키 검사를 `record.key() != payload.channelId.toString()`으로 되돌린다.
 */
object ProjectionEntityKey {
    /**
     * `channel.event`의 키.
     * 은퇴 포맷: `<teamId>` (`9a51d22a`~), DM 채널의 `dm-<channelId>` (`8e0d97bb`~`e03d5113`).
     */
    fun matchesChannelEvent(messageKey: String?, channelId: Long, teamId: Long?): Boolean =
        messageKey == channelId.toString() ||
            (teamId != null && messageKey == teamId.toString()) ||
            messageKey == "dm-$channelId"
}
