import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ProjectPermissionProjection} from '../schema';
import {projectionSourceVersion, PROJECTION_EPOCH} from './versioned-projection.util';

@Injectable()
export class ProjectPermissionProjectionRepository {
    constructor(@InjectModel(ProjectPermissionProjection.name) private readonly model: Model<ProjectPermissionProjection>) {}

    async canRead(projectId: number, userId: number): Promise<boolean> {
        return (await this.model.exists({projectId, userId, read: true})) !== null;
    }

    async upsert(
        projectId: number,
        userId: number,
        canRead: boolean,
        canWrite: boolean,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<void> {
        const newer = {$gt: [sourceVersion, projectionSourceVersion]};
        const equal = {$eq: [sourceVersion, projectionSourceVersion]};
        const permission = (field: string, isAllowed: boolean) => ({
            $cond: [
                newer,
                isAllowed,
                {$cond: [equal, {$and: [{$ifNull: [`$${field}`, false]}, isAllowed]}, {$ifNull: [`$${field}`, false]}]},
            ],
        });
        await this.model.updateOne({projectId, userId}, [{
            $set: {
                projectId,
                userId,
                read: permission('read', canRead),
                write: permission('write', canWrite),
                sourceOccurredAt: {$cond: [newer, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}]},
                sourceVersion: {$cond: [newer, sourceVersion, projectionSourceVersion]},
            },
        }], {upsert: true, timestamps: false, updatePipeline: true});
    }
}
