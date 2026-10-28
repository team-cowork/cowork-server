import {appendAuthorMarker, extractCommentAuthor} from './comment-author-marker';

describe('comment-author-marker', () => {
    describe('extractCommentAuthor', () => {
        it('마커가 있으면 실제 작성자와 마커를 제거한 본문을 반환한다', () => {
            const result = extractCommentAuthor(
                '확인했습니다\n\n<!-- cowork:author=real-author -->',
                'cowork-bot',
            );

            expect(result).toEqual({author: 'real-author', body: '확인했습니다'});
        });

        it('마커가 없으면 fallback 사용자를 작성자로, 본문을 그대로 반환한다', () => {
            const result = extractCommentAuthor('그냥 GitHub에서 단 댓글', 'octocat');

            expect(result).toEqual({author: 'octocat', body: '그냥 GitHub에서 단 댓글'});
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
            expect(extractCommentAuthor(marked, 'cowork-bot')).toEqual({
                author: 'real-author',
                body: '새 댓글',
                operationId: 'op-1',
            });
        });
    });
});
