import {githubField} from '../github-field.js';
import {toTimelineActivity} from './timeline-activity.js';

const created = '2026-01-01T00:00:00Z';

describe('toTimelineActivity', () => {
	it('커밋은 작성 시각과 다른 커미터 시각을 쓰고 오프셋을 UTC로 변환한다', () => {
		expect(toTimelineActivity({
			event: 'committed', sha: 'abc', author: {name: 'dev', date: created},
			committer: {name: 'bot', date: '2026-01-03T01:00:00+09:00'}, message: 'fix',
		}, '42')).toMatchObject({
			id: 'committed:abc', actor: 'dev', occurredAt: '2026-01-02T16:00:00.000Z', body: 'fix', commitSha: 'abc',
		});
	});

	it('제출된 리뷰는 상태와 제출 시각을 쓰고 미제출 리뷰는 제외한다', () => {
		expect(toTimelineActivity({
			event: 'reviewed', id: 8, [githubField.submittedAt]: created, user: {login: 'alice'}, state: 'approved',
		}, '42')).toMatchObject({
			actor: 'alice', reviewId: 8, reviewState: 'approved', occurredAt: '2026-01-01T00:00:00.000Z',
		});
		expect(toTimelineActivity({event: 'reviewed', state: 'pending'}, '42')).toBeUndefined();
	});

	it('자체 앱이 작성한 댓글의 실제 작성자만 복원한다', () => {
		const comment = {
			event: 'commented', id: 1, [githubField.createdAt]: created, user: {login: 'bot'}, body: 'hello\n\n<!-- cowork:author=alice -->',
		};
		expect(toTimelineActivity({...comment, [githubField.performedViaGithubApp]: {id: 42}}, '42')).toMatchObject({actor: 'alice', body: 'hello'});
		expect(toTimelineActivity(comment, '42')).toMatchObject({actor: 'bot', body: comment.body});
	});

	it.each(['labeled', 'unlabeled'])('%s 활동에 라벨을 보존한다', event => {
		expect(toTimelineActivity({
			event, id: 3, actor: {login: 'alice'}, [githubField.createdAt]: created, label: {name: 'bug', color: 'ff0000'},
		}, '42')).toMatchObject({type: event, labelName: 'bug', labelColor: 'ff0000'});
	});

	it('코드 줄이 변경되어도 리뷰 댓글의 원래 줄을 제공한다', () => {
		expect(toTimelineActivity({
			event: 'review_commented', id: 7, path: 'app.kt', [githubField.originalLine]: 4, [githubField.createdAt]: created,
		}, '42')).toMatchObject({
			type: 'review_commented', commentId: 7, path: 'app.kt', line: 4,
		});
	});
});
