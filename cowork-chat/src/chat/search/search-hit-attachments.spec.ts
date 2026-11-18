import {Types} from 'mongoose';
import {type SearchHit} from '../../search/elasticsearch.service.js';
import {attachSearchHitAttachments} from './search-hit-attachments.js';

const firstId = new Types.ObjectId();
const secondId = new Types.ObjectId();

function hit(messageId: Types.ObjectId, channelId: number): SearchHit {
	return {
		messageId: messageId.toString(),
		channelId,
		authorId: 1,
		content: '배포',
		highlight: [],
		type: 'FILE',
		hasAttachments: true,
		isPinned: false,
		createdAt: '2026-11-18T00:00:00.000Z',
	};
}

const image = {
	name: 'a.png', objectKey: 'chat-files/1/2/a.png', size: 10, mimeType: 'image/png',
};

describe('attachSearchHitAttachments', () => {
	it('검색 결과 순서대로 원본 메시지의 첨부를 붙이고 spoiler 값이 없으면 false로 채운다', () => {
		const result = attachSearchHitAttachments(
			[hit(firstId, 20), hit(secondId, 20)],
			[
				{_id: secondId, channelId: 20, attachments: [{...image, spoiler: true}]},
				{_id: firstId, channelId: 20, attachments: [image]},
			],
		);

		expect(result.map(item => item.messageId)).toEqual([firstId.toString(), secondId.toString()]);
		expect(result[0].attachments).toEqual([{...image, spoiler: false}]);
		expect(result[1].attachments).toEqual([{...image, spoiler: true}]);
	});

	it('원본 메시지를 찾지 못하면 빈 첨부 목록을 붙인다', () => {
		const result = attachSearchHitAttachments([hit(firstId, 20)], []);

		expect(result[0].attachments).toEqual([]);
	});

	it('원본 메시지의 채널이 검색 결과의 채널과 다르면 첨부를 붙이지 않는다', () => {
		const result = attachSearchHitAttachments(
			[hit(firstId, 20)],
			[{_id: firstId, channelId: 99, attachments: [image]}],
		);

		expect(result[0].attachments).toEqual([]);
	});

	it('이전 URL 필드는 응답에 담지 않는다', () => {
		const result = attachSearchHitAttachments(
			[hit(firstId, 20)],
			[{_id: firstId, channelId: 20, attachments: [{...image, url: 'https://legacy.example/a.png'}]}],
		);

		expect(result[0].attachments[0]).not.toHaveProperty('url');
	});
});
