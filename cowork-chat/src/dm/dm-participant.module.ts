import {Module as nestModule} from '@nestjs/common';
import {ConfigModule} from '@nestjs/config';
import {MongooseModule} from '@nestjs/mongoose';
import {DmParticipant, dmParticipantSchema} from '../chat/schema/dm-participant.schema.js';
import {DmParticipantConsumer} from './dm-participant.consumer.js';

@nestModule({
	imports: [
		ConfigModule,
		MongooseModule.forFeature([{name: DmParticipant.name, schema: dmParticipantSchema}]),
	],
	providers: [DmParticipantConsumer],
	exports: [DmParticipantConsumer],
})
export class DmParticipantModule {}
