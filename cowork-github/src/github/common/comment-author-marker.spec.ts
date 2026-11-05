import {githubField} from '../github-field.js';
import {appendAuthorMarker, isAppComment, readCommentAuthor} from './comment-author-marker.js';

describe('comment-author-marker', () => {
	describe('readCommentAuthor', () => {
		const appComment = {user: {login: 'cowork-bot'}, [githubField.performedViaGithubApp]: {id: 123}};

		it('앱 댓글에 마커가 있으면 실제 작성자와 마커를 제거한 본문을 반환한다', () => {
			const result = readCommentAuthor(
				{...appComment, body: '확인했습니다\n\n<!-- cowork:author=real-author -->'},
				'123',
			);

			expect(result).toEqual({author: 'real-author', body: '확인했습니다'});
		});

		it('마커가 없으면 user.login을 작성자로, 본문을 그대로 반환한다', () => {
			const result = readCommentAuthor({...appComment, body: '그냥 GitHub에서 단 댓글'}, '123');

			expect(result).toEqual({author: 'cowork-bot', body: '그냥 GitHub에서 단 댓글'});
		});

		it('앱이 작성하지 않은 댓글이면 마커를 무시하고 user.login과 본문을 그대로 반환한다', () => {
			const body = '확인했습니다\n\n<!-- cowork:author=alice operation=op-1 -->';

			expect(readCommentAuthor({body, user: {login: 'outsider'}}, '123')).toEqual({author: 'outsider', body});
		});

		it('user가 없으면 ghost를 작성자로 쓴다', () => {
			expect(readCommentAuthor({body: 'hi'}, '123')).toEqual({author: 'ghost', body: 'hi'});
		});
	});

	describe('isAppComment', () => {
		it('performed_via_github_app.id가 앱 ID와 같을 때만 true를 반환한다', () => {
			expect(isAppComment({id: 123}, '123')).toBe(true);
			expect(isAppComment({id: 999}, '123')).toBe(false);
			expect(isAppComment(null, '123')).toBe(false);
			expect(isAppComment(undefined, '123')).toBe(false);
			expect(isAppComment({}, '123')).toBe(false);
		});
	});

	describe('appendAuthorMarker', () => {
		it('마커가 없는 본문 끝에 마커를 추가한다', () => {
			const result = appendAuthorMarker('새 댓글', 'real-author');

			expect(result).toBe('새 댓글\n\n<!-- cowork:author=real-author -->');
		});

		it('기존 마커를 제거하고 새 마커로 교체한다', () => {
			const result = appendAuthorMarker(
				'수정된 댓글\n\n<!-- cowork:author=old-author -->',
				'new-author',
			);

			expect(result).toBe('수정된 댓글\n\n<!-- cowork:author=new-author -->');
		});

		it('operationId를 함께 남기면 읽을 때 작성자와 operationId를 모두 돌려주고 본문에서 제거한다', () => {
			const marked = appendAuthorMarker('새 댓글', 'real-author', 'op-1');

			expect(marked).toBe('새 댓글\n\n<!-- cowork:author=real-author operation=op-1 -->');
			expect(readCommentAuthor({body: marked, [githubField.performedViaGithubApp]: {id: 123}}, '123')).toEqual({
				author: 'real-author',
				body: '새 댓글',
				operationId: 'op-1',
			});
		});
	});
});
