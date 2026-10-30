import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

/**
`preference.team-setting.state.v1`의 팀 설정 projection. 메시지 전송에 쓰는 `tagSpamBlock`만 담는다.
*/
@schema({timestamps: true, versionKey: false})
export class TeamSettingProjection {
	@prop({required: true}) teamId!: number;
	@prop({required: true, default: false}) tagSpamBlock!: boolean;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const teamSettingProjectionSchema = SchemaFactory.createForClass(TeamSettingProjection)
	.index({teamId: 1}, {unique: true});
