import {ServiceUnavailableException} from '@nestjs/common';
import {ProjectClient} from './project.client';

describe('ProjectClient project read permission', () => {
    const permission = {canRead: jest.fn()};
    const projects = {findById: jest.fn()};
    const members = {findByTeamIdsAndUserIds: jest.fn()};
    const readiness = {isReady: jest.fn()};
    let service: ProjectClient;

    beforeEach(() => {
        jest.resetAllMocks();
        service = new ProjectClient(permission as never, {} as never, projects as never, members as never, readiness as never);
        readiness.isReady.mockReturnValue(true);
        projects.findById.mockResolvedValue({projectId: 7, teamId: 3, createdBy: 1});
        members.findByTeamIdsAndUserIds.mockResolvedValue([{
            teamId: 3, userId: 217, role: 'MEMBER', sourceVersion: 1000n,
        }]);
        permission.canRead.mockResolvedValue(false);
    });

    it('allows read from permission without a project member row', async () => {
        permission.canRead.mockResolvedValue(true);
        await expect(service.canRead(7, 217)).resolves.toBe(true);
    });

    it('denies team membership without read permission', async () => {
        await expect(service.canRead(7, 217)).resolves.toBe(false);
    });

    it('denies a former team member even if permission remains', async () => {
        members.findByTeamIdsAndUserIds.mockResolvedValue([]);
        permission.canRead.mockResolvedValue(true);
        await expect(service.canRead(7, 217)).resolves.toBe(false);
        expect(permission.canRead).not.toHaveBeenCalled();
    });

    it('requires permission even for the project creator', async () => {
        projects.findById.mockResolvedValue({projectId: 7, teamId: 3, createdBy: 217});
        await expect(service.canRead(7, 217)).resolves.toBe(false);
    });

    it('binds a project grant to the active membership version after rejoining', async () => {
        permission.canRead.mockImplementation(async (_projectId, _userId, sourceVersion) => sourceVersion === 1000n);
        await expect(service.canRead(7, 217)).resolves.toBe(true);
        members.findByTeamIdsAndUserIds.mockResolvedValue([{
            teamId: 3, userId: 217, role: 'MEMBER', sourceVersion: 2000n,
        }]);
        await expect(service.canRead(7, 217)).resolves.toBe(false);
        expect(permission.canRead).toHaveBeenLastCalledWith(7, 217, 2000n);
    });

    it('denies legacy membership without its source version', async () => {
        members.findByTeamIdsAndUserIds.mockResolvedValue([{teamId: 3, userId: 217, role: 'MEMBER'}]);
        permission.canRead.mockResolvedValue(true);
        await expect(service.canRead(7, 217)).resolves.toBe(false);
        expect(permission.canRead).not.toHaveBeenCalled();
    });

    it.each(['OWNER', 'ADMIN'])('allows active built-in %s without a separate grant', async role => {
        members.findByTeamIdsAndUserIds.mockResolvedValue([{teamId: 3, userId: 217, role}]);
        await expect(service.canRead(7, 217)).resolves.toBe(true);
    });

    it('fails closed while permissions are catching up', async () => {
        readiness.isReady.mockReturnValue(false);
        await expect(service.canRead(7, 217)).rejects.toBeInstanceOf(ServiceUnavailableException);
    });
});
