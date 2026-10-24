import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ProjectPermissionProjection} from '../schema';
import {projectionSourceVersion, PROJECTION_EPOCH} from './versioned-projection.util';

@Injectable()
export class ProjectPermissionProjectionRepository {
    constructor(@InjectModel(ProjectPermissionProjection.name) private readonly model: Model<ProjectPermissionProjection>) {}

    async canRead(projectId: number, userId: number, membershipVersion: bigint | mongo.Long): Promise<boolean> {
        const sourceMembershipVersion = typeof membershipVersion === 'bigint' ? membershipVersion : membershipVersion.toBigInt();
        return sourceMembershipVersion > 0n
            && (await this.model.exists({
                projectId, userId, read: true, sourceMembershipVersion,
            })) !== null;
    }

    async upsert(
        projectId: number,
        userId: number,
        canRead: boolean,
        canWrite: boolean,
        occurredAt: Date,
        sourceVersion: mongo.Long,
        // TODO(#596): 구버전 이벤트 재생·rollback 종료 후 호출자가 stamp를 필수 전달하도록 이 인자 기본값만 제거한다.
        sourceMembershipVersion: mongo.Long = mongo.Long.ZERO,
    ): Promise<void> {
        const newer = {$gt: [sourceVersion, projectionSourceVersion]};
        const equal = {$eq: [sourceVersion, projectionSourceVersion]};
        const sameMembership = {
            $eq: [
                {$ifNull: ['$sourceMembershipVersion', mongo.Long.ZERO]}, sourceMembershipVersion,
            ],
        };
        const permission = (field: string, isAllowed: boolean) => ({
            $cond: [
                newer,
                isAllowed,
                {$cond: [equal, {$and: [sameMembership, {$ifNull: [`$${field}`, false]}, isAllowed]}, {$ifNull: [`$${field}`, false]}]},
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
                sourceMembershipVersion: {
                    $cond: [
                        newer,
                        sourceMembershipVersion,
                        {$ifNull: ['$sourceMembershipVersion', mongo.Long.ZERO]},
                    ],
                },
            },
        }], {upsert: true, timestamps: false, updatePipeline: true});
    }
}
