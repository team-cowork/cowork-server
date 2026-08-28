import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type UserProfileProjectionDocument = HydratedDocument<UserProfileProjection>;

/** `user.profile.event`로 동기화되는 사용자 표시 정보 읽기 모델. */
@Schema({ timestamps: true, versionKey: false })
export class UserProfileProjection {
    @Prop({ required: true }) userId!: number;
    @Prop({ required: true, default: '' }) name!: string;
    @Prop({ type: String, default: null }) nickname!: string | null;
    @Prop({ type: String, default: null }) githubId!: string | null;
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const UserProfileProjectionSchema = SchemaFactory.createForClass(UserProfileProjection);
UserProfileProjectionSchema.index({ userId: 1 }, { unique: true });
