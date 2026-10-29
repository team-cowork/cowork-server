import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

/**
`preference.team-role.state.v2`의 role 정의 projection. `permissions`는 권한 키별 boolean 전체 상태다.
*/
@schema({timestamps: true, versionKey: false})
export class TeamRoleProjection {
	@prop({required: true}) teamId!: number;
	@prop({required: true}) roleId!: number;
	@prop({required: true, default: ''}) name!: string;
	@prop({required: true, default: 0}) priority!: number;
	@prop({required: true, default: false}) isDefault!: boolean;
	@prop({required: true, type: Object, default: {}}) permissions!: Record<string, boolean>;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const teamRoleProjectionSchema = SchemaFactory.createForClass(TeamRoleProjection)
	.index({teamId: 1, roleId: 1}, {unique: true})
	.index({roleId: 1, deleted: 1})
	.index({teamId: 1, isDefault: 1, deleted: 1});
