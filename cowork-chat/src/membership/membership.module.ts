import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { MongooseModule } from '@nestjs/mongoose';
import { MembershipConsumer } from './membership.consumer';
import { ChannelMember, ChannelMemberSchema } from '../chat/schema/channel-member.schema';

@Module({
    imports: [
        ConfigModule,
        MongooseModule.forFeature([{ name: ChannelMember.name, schema: ChannelMemberSchema }]),
    ],
    providers: [MembershipConsumer],
    exports: [MembershipConsumer],
})
export class MembershipModule {}
