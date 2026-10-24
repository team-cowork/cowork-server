import {UserClient} from './user.client';

describe('UserClient display names', () => {
    const profiles = {findByUserIds: jest.fn()};
    const members = {findNicknames: jest.fn()};
    let client: UserClient;

    beforeEach(() => {
        jest.resetAllMocks();
        client = new UserClient(profiles as never, members as never);
        profiles.findByUserIds.mockResolvedValue([
            {userId: 1, name: '김철수', nickname: '철수'},
            {userId: 2, name: '이영희', nickname: null},
        ]);
        members.findNicknames.mockResolvedValue(new Map([[1, '김철수 / 백엔드']]));
    });

    it('prefers the team nickname over the account nickname and name', async () => {
        const names = await client.getDisplayNames([1, 2], 3);

        expect(names).toEqual(new Map([[1, '김철수 / 백엔드'], [2, '이영희']]));
        expect(members.findNicknames).toHaveBeenCalledWith(3, [1, 2]);
    });

    it('uses account names without a team context', async () => {
        const names = await client.getDisplayNames([1, 2]);

        expect(names).toEqual(new Map([[1, '철수'], [2, '이영희']]));
        expect(members.findNicknames).not.toHaveBeenCalled();
    });
});
