/**
 * compacted state topic의 메시지 키는 영구 계약이다. 키 포맷을 `<parentId>`에서
 * `<parentId>:<childId>`로 바꾼 뒤에도 legacy 키 레코드는 서로 다른 키 공간에 남아
 * compaction으로 사라지지 않으므로, earliest부터의 재생이 성립하려면 두 포맷을 모두 인정해야 한다.
 */
export function matchesCompositeEntityKey(
    messageKey: string | undefined,
    parentId: number,
    childId: number,
): boolean {
    return messageKey === `${parentId}:${childId}` || messageKey === `${parentId}`;
}
