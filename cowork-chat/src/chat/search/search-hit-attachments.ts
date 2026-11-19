import {type SearchHit} from '../../search/elasticsearch.service.js';
import {publicAttachments, type MessageAttachmentsRow} from '../repository/message.repository.js';

/**
 검색 결과 한 건에 원본 메시지의 첨부를 붙인 형태
 */
export type SearchHitWithAttachments = SearchHit & {attachments: ReturnType<typeof publicAttachments>};

/**
 검색 결과에 원본 메시지의 첨부를 붙인다.

 검색 색인에는 첨부가 없으므로 원본 메시지에서 읽은 첨부를 메시지 ID로 맞춰 붙인다.
 원본을 찾지 못했거나 원본의 채널이 검색 결과의 채널과 다르면 빈 배열을 붙인다.
 채널이 다른 원본의 첨부를 붙이면 읽을 권한이 없는 채널의 첨부가 섞일 수 있기 때문이다.

 @returns 순서를 유지한 검색 결과. 각 첨부의 `spoiler`는 값이 없으면 `false`
 */
export function attachSearchHitAttachments(hits: SearchHit[], rows: MessageAttachmentsRow[]): SearchHitWithAttachments[] {
	const rowsById = new Map(rows.map(row => [row._id.toString(), row]));
	return hits.map(hit => {
		const row = rowsById.get(hit.messageId);
		const attachments = row?.channelId === hit.channelId ? publicAttachments(row.attachments ?? []) : [];
		return {...hit, attachments};
	});
}
