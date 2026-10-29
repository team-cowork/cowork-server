import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
`user.profile.event.v2`로 동기화되는 사용자 표시 정보 읽기 모델.
*/
@schema({timestamps: true, versionKey: false})
export class UserProfileProjection {
	@prop({required: true}) userId!: number;
	@prop({required: true, default: ''}) name!: string;
	@prop({type: String, default: null}) nickname!: Nullable<string>;
	@prop({type: String, default: null}) githubId!: Nullable<string>;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const userProfileProjectionSchema = SchemaFactory.createForClass(UserProfileProjection)
	.index({userId: 1}, {unique: true});
