import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../type/nullable.js';

/**
계약을 위반했지만 원문을 보존한 뒤에만 건너뛴 Kafka projection record.
*/
@schema({timestamps: true, versionKey: false, collection: 'projection_quarantine_records'})
export class ProjectionQuarantineRecord {
	@prop({required: true}) groupId!: string;
	@prop({required: true}) topic!: string;
	@prop({required: true}) partition!: number;
	@prop({required: true}) messageOffset!: bigint;
	@prop({type: String, default: null}) eventKey!: Nullable<string>;
	@prop({type: String, default: null}) payload!: Nullable<string>;
	@prop({required: true}) reason!: string;
}

export const projectionQuarantineRecordSchema = SchemaFactory.createForClass(ProjectionQuarantineRecord)
	.index(
		{
			groupId: 1, topic: 1, partition: 1, messageOffset: 1,
		},
		{unique: true},
	);
