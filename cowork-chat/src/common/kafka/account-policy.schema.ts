import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

@schema({versionKey: false})
export class AccountPolicy {
	@prop({required: true, unique: true}) key!: string;
	@prop({required: true}) kind!: string;
	@prop({required: true}) roleId!: number;
	@prop() accountId?: number;
	@prop({type: Object}) permissions?: Record<string, boolean>;
	@prop() isDefault?: boolean;
	@prop({required: true}) deleted!: boolean;
	@prop({required: true}) sourceVersion!: bigint;
}

export const accountPolicySchema = SchemaFactory.createForClass(AccountPolicy)
	.index({kind: 1, accountId: 1, deleted: 1})
	.index({kind: 1, roleId: 1, deleted: 1});
