import {Module} from '@nestjs/common';
import {ConfigModule} from '@nestjs/config';
import {MongooseModule} from '@nestjs/mongoose';
import {DmParticipant, DmParticipantSchema} from '../chat/schema/dm-participant.schema';
import {DmParticipantConsumer} from './dm-participant.consumer';

@Module({
    imports: [
        ConfigModule,
        MongooseModule.forFeature([{name: DmParticipant.name, schema: DmParticipantSchema}]),
    ],
    providers: [DmParticipantConsumer],
    exports: [DmParticipantConsumer],
})
export class DmParticipantModule {}
