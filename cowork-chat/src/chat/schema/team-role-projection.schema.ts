import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';

/**
`preference.team-role.state.v2`의 role 정의 projection. `permissions`는 권한 키별 boolean 전체 상태다.
*/
@Schema({timestamps: true, versionKey: false})
export class TeamRoleProjection {
    @Prop({required: true}) teamId!: number;
    @Prop({required: true}) roleId!: number;
    @Prop({required: true, default: ''}) name!: string;
    @Prop({required: true, default: 0}) priority!: number;
    @Prop({required: true, default: false}) isDefault!: boolean;
    @Prop({required: true, type: Object, default: {}}) permissions!: Record<string, boolean>;
    @Prop({required: true, default: false}) deleted!: boolean;
    @Prop({required: true, type: Date}) sourceOccurredAt!: Date;
    @Prop({required: true}) sourceVersion!: bigint;
}

export const TeamRoleProjectionSchema = SchemaFactory.createForClass(TeamRoleProjection);
TeamRoleProjectionSchema.index({teamId: 1, roleId: 1}, {unique: true});
TeamRoleProjectionSchema.index({roleId: 1, deleted: 1});
TeamRoleProjectionSchema.index({teamId: 1, isDefault: 1, deleted: 1});
