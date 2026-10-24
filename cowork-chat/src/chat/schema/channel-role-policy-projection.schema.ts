import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';

/**
`preference.channel-role-policy.state.v2`의 role×channel 권한 덮어쓰기 projection.
`permissions`에 없는 키는 역할 기본 권한을 따른다.
*/
@Schema({timestamps: true, versionKey: false})
export class ChannelRolePolicyProjection {
    @Prop({required: true}) teamId!: number;
    @Prop({required: true}) channelId!: number;
    @Prop({required: true}) roleId!: number;
    @Prop({type: Object, default: null}) permissions!: Record<string, boolean> | null;
    @Prop({required: true, default: false}) deleted!: boolean;
    @Prop({required: true, type: Date}) sourceOccurredAt!: Date;
    @Prop({required: true}) sourceVersion!: bigint;
}

export const ChannelRolePolicyProjectionSchema = SchemaFactory.createForClass(ChannelRolePolicyProjection);
ChannelRolePolicyProjectionSchema.index({teamId: 1, channelId: 1, roleId: 1}, {unique: true});
ChannelRolePolicyProjectionSchema.index({roleId: 1, channelId: 1, deleted: 1});
