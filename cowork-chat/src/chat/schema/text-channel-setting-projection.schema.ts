import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

/**
`preference.text-channel-setting.state.v1`의 텍스트 채널 설정 projection. `slowmodeSeconds`가 0이면 슬로우 모드가 꺼져 있다.
*/
@schema({timestamps: true, versionKey: false})
export class TextChannelSettingProjection {
	@prop({required: true}) channelId!: number;
	@prop({required: true, default: 0}) slowmodeSeconds!: number;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const textChannelSettingProjectionSchema = SchemaFactory.createForClass(TextChannelSettingProjection)
	.index({channelId: 1}, {unique: true});
