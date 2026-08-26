import { UserProfileProjectionRepository } from '../repository/user-profile-projection.repository';
import { UserClient } from './user.client';

describe('UserClient', () => {
    const projectionRepository = {
        findByUserIds: jest.fn(),
    };
    const client = new UserClient(projectionRepository as unknown as UserProfileProjectionRepository);

    beforeEach(() => jest.clearAllMocks());

    it('빈 배열이면 projection을 조회하지 않는다', async () => {
        await expect(client.getDisplayNames([])).resolves.toEqual(new Map());
        expect(projectionRepository.findByUserIds).not.toHaveBeenCalled();
    });

    it('nickname을 우선하고 없으면 name을 표시명으로 사용한다', async () => {
        projectionRepository.findByUserIds.mockResolvedValue([
            { userId: 1, name: '홍길동', nickname: '길동이' },
            { userId: 2, name: '김철수', nickname: null },
        ]);

        await expect(client.getDisplayNames([1, 2, 1])).resolves.toEqual(new Map([
            [1, '길동이'],
            [2, '김철수'],
        ]));
        expect(projectionRepository.findByUserIds).toHaveBeenCalledWith([1, 2]);
    });

    it('projection에 없는 사용자는 결과에서 생략한다', async () => {
        projectionRepository.findByUserIds.mockResolvedValue([{ userId: 1, name: '홍길동', nickname: null }]);

        await expect(client.getDisplayNames([1, 999])).resolves.toEqual(new Map([[1, '홍길동']]));
    });
});
