import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

/**
`preference.team-role.state.v2`의 account↔role assignment projection.
*/
@schema({timestamps: true, versionKey: false})
export class TeamRoleAssignmentProjection {
	@prop({required: true}) teamId!: number;
	@prop({required: true}) accountId!: number;
	@prop({required: true}) roleId!: number;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const teamRoleAssignmentProjectionSchema = SchemaFactory.createForClass(TeamRoleAssignmentProjection)
	.index({teamId: 1, accountId: 1, roleId: 1}, {unique: true})
	.index({roleId: 1, deleted: 1});
