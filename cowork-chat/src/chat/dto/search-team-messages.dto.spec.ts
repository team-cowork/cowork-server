import {plainToInstance} from 'class-transformer';
import {validate} from 'class-validator';
import {SearchTeamMessagesDto} from './search-team-messages.dto.js';

async function archivedFilter(archived: unknown) {
	const dto = plainToInstance(SearchTeamMessagesDto, {q: '배포', teamId: '10', archived});
	const errors = await validate(dto);
	return {archived: dto.archived, errors: errors.filter(error => error.property === 'archived')};
}

describe('SearchTeamMessagesDto archived', () => {
	it('true와 false 문자열은 boolean으로 바꾼다', async () => {
		await expect(archivedFilter('true')).resolves.toEqual({archived: true, errors: []});
		await expect(archivedFilter('false')).resolves.toEqual({archived: false, errors: []});
	});

	it('생략하면 필터 없이 통과한다', async () => {
		await expect(archivedFilter(undefined)).resolves.toEqual({archived: undefined, errors: []});
	});

	it('지원하지 않는 값은 활성 채널 검색으로 바꾸지 않고 거부한다', async () => {
		const results = await Promise.all(['tru', 'garbage', ''].map(async invalid => archivedFilter(invalid)));

		for (const {errors} of results) {
			expect(errors).toHaveLength(1);
		}
	});
});
