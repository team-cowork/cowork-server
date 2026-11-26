import type {HttpService} from '@nestjs/axios';
import {of} from 'rxjs';
import type {GithubAuthService} from '../auth/github-auth.service.js';
import {githubField} from '../github-field.js';
import {TimelineApiClient} from './timeline-api.client.js';

describe('TimelineApiClient', () => {
	const auth = {getInstallationToken: jest.fn().mockResolvedValue('test')} as unknown as GithubAuthService;

	it('PR의 타임라인과 줄 댓글은 각 목록의 Link가 끝날 때까지 조회한다', async () => {
		const get = jest.fn()
			.mockReturnValueOnce(of({data: {[githubField.pullRequest]: {}}}))
			.mockReturnValueOnce(of({data: [{event: 'committed'}], headers: {link: '<next>; rel="next"'}}))
			.mockReturnValueOnce(of({data: [{event: 'labeled'}], headers: {}}))
			.mockReturnValueOnce(of({data: [{id: 3}], headers: {}}));
		const client = new TimelineApiClient({get} as unknown as HttpService, auth);
		const parent = await client.parent('org', 'repo', 7);
		const pages = client.pages('org', 'repo', 7, parent);
		expect(await pages.next()).toMatchObject({value: [{event: 'committed'}]});
		expect(await pages.next()).toMatchObject({value: [{event: 'labeled'}]});
		expect(await pages.next()).toMatchObject({value: [{id: 3, event: 'review_commented'}]});
		expect(await pages.next()).toMatchObject({done: true});
		expect(get).toHaveBeenNthCalledWith(3, 'https://api.github.com/repos/org/repo/issues/7/timeline', expect.objectContaining({params: {[githubField.perPage]: 100, page: 2}}));
		expect(get).toHaveBeenNthCalledWith(4, 'https://api.github.com/repos/org/repo/pulls/7/comments', expect.objectContaining({maxRedirects: 0}));
	});

	it('이슈에는 PR 리뷰 댓글 요청을 보내지 않는다', async () => {
		const get = jest.fn()
			.mockReturnValueOnce(of({data: {}}))
			.mockReturnValueOnce(of({data: [], headers: {}}));
		const client = new TimelineApiClient({get} as unknown as HttpService, auth);
		const parent = await client.parent('org', 'repo', 8);
		const pages = client.pages('org', 'repo', 8, parent);
		expect(await pages.next()).toMatchObject({value: []});
		expect(await pages.next()).toMatchObject({done: true});
		expect(get).toHaveBeenCalledTimes(2);
	});
});
