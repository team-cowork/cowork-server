import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';

/**
`preference.text-channel-setting.state.v1`의 텍스트 채널 설정 projection. `slowmodeSeconds`가 0이면 슬로우 모드가 꺼져 있다.
*/
@Schema({timestamps: true, versionKey: false})
export class TextChannelSettingProjection {
    @Prop({required: true}) channelId!: number;
    @Prop({required: true, default: 0}) slowmodeSeconds!: number;
    @Prop({required: true, default: false}) deleted!: boolean;
    @Prop({required: true, type: Date}) sourceOccurredAt!: Date;
    @Prop({required: true}) sourceVersion!: bigint;
}

export const TextChannelSettingProjectionSchema = SchemaFactory.createForClass(TextChannelSettingProjection);
TextChannelSettingProjectionSchema.index({channelId: 1}, {unique: true});
