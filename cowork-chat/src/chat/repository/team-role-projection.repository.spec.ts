import { mongo } from 'mongoose';
import { TeamRoleProjectionRepository } from './team-role-projection.repository';

const query = (value: unknown) => ({ lean: jest.fn().mockResolvedValue(value) });

describe('TeamRoleProjectionRepository member tombstone ordering', () => {
    const roleModel = { updateOne: jest.fn(), find: jest.fn() };
    const assignmentModel = { updateOne: jest.fn(), updateMany: jest.fn(), find: jest.fn() };
    const tombstoneModel = { exists: jest.fn(), updateOne: jest.fn(), find: jest.fn() };
    const repository = new TeamRoleProjectionRepository(
        roleModel as never,
        assignmentModel as never,
        tombstoneModel as never,
    );

    beforeEach(() => jest.clearAllMocks());

    it('같은 version tombstone 재처리에서도 남아 있는 active assignment를 다시 tombstone 처리한다', async () => {
        tombstoneModel.exists.mockResolvedValue(null);
        tombstoneModel.updateOne.mockResolvedValue({ modifiedCount: 0, upsertedCount: 0 });
        assignmentModel.updateMany.mockResolvedValue({ modifiedCount: 1 });
        const version = mongo.Long.fromNumber(100);

        await expect(repository.deleteMemberAssignments(1, 2, new Date(0), version)).resolves.toBe(true);

        expect(assignmentModel.updateMany).toHaveBeenCalledTimes(1);
    });

    it('assignment과 member tombstone이 다른 partition 순서로 보이더라도 read에서 tombstone 이하 assignment를 제외한다', async () => {
        assignmentModel.find.mockReturnValue(query([
            { teamId: 1, accountId: 2, roleId: 10, sourceVersion: 99n },
            { teamId: 1, accountId: 3, roleId: 20, sourceVersion: 101n },
        ]));
        tombstoneModel.find.mockReturnValue(query([
            { teamId: 1, accountId: 2, sourceVersion: 100n },
            { teamId: 1, accountId: 3, sourceVersion: 100n },
        ]));

        await expect(repository.findAssignmentsByTeamIdsAndAccountIds([1], [2, 3])).resolves.toEqual([
            { teamId: 1, accountId: 3, roleId: 20 },
        ]);
    });
});
