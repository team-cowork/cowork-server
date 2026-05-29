import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { ChannelMember } from '../schema/channel-member.schema';

/**
 * 채널 멤버십 도큐먼트에 대한 데이터 접근 객체.
 *
 * MongoDB의 `channel_members` 컬렉션을 대상으로 하며,
 * 멤버십 존재 여부 확인, 팀·채널 ID 조회 등 읽기 위주의 쿼리를 제공합니다.
 */
@Injectable()
export class ChannelMemberRepository {
    constructor(
        @InjectModel(ChannelMember.name) private readonly memberModel: Model<ChannelMember>,
    ) {}

    /**
     * 특정 사용자가 채널에 가입되어 있는지 확인합니다.
     *
     * `Model.exists()`를 사용하므로 전체 도큐먼트를 로드하지 않고
     * `_id`만 반환받아 메모리 효율을 높입니다.
     *
     * @param channelId - 확인할 채널의 식별자
     * @param userId - 확인할 사용자의 식별자
     * @returns 멤버로 등록되어 있으면 `true`, 그렇지 않으면 `false`
     */
    async exists(channelId: number, userId: number): Promise<boolean> {
        const member = await this.memberModel.exists({ channelId, userId });
        return member !== null;
    }

    /**
     * 채널과 사용자 ID로 해당 멤버십이 속한 팀 ID를 조회합니다.
     *
     * `teamId` 필드만 projection하여 불필요한 데이터 전송을 최소화합니다.
     *
     * @param channelId - 조회할 채널의 식별자
     * @param userId - 조회할 사용자의 식별자
     * @returns 팀 ID. 멤버십이 존재하지 않으면 `null`
     */
    async findTeamIdByChannelAndUser(channelId: number, userId: number): Promise<number | null> {
        const member = await this.memberModel.findOne({ channelId, userId }, { teamId: 1 });
        return member?.teamId ?? null;
    }

    /**
     * 특정 사용자가 가입된 모든 채널 ID 목록을 반환합니다.
     *
     * `.lean()`을 사용하여 Mongoose 래퍼 없이 순수 JS 객체로 반환하므로
     * 대량 데이터 조회 시 성능이 향상됩니다.
     *
     * @param userId - 채널 목록을 조회할 사용자의 식별자
     * @returns 해당 사용자가 속한 채널 ID 배열. 가입된 채널이 없으면 빈 배열
     */
    async findChannelIdsByUser(userId: number): Promise<number[]> {
        const memberships = await this.memberModel.find({ userId }, { channelId: 1 }).lean();
        return memberships.map((membership) => membership.channelId);
    }

    /**
     * 특정 채널에 속한 모든 멤버 도큐먼트를 반환합니다.
     *
     * `.lean()`을 사용하여 순수 JS 객체 배열로 반환합니다.
     * 반환된 객체는 Mongoose 인스턴스 메서드(`save()` 등)를 사용할 수 없습니다.
     *
     * @param channelId - 멤버 목록을 조회할 채널의 식별자
     * @returns 채널에 속한 {@link ChannelMember} 객체 배열
     */
    findByChannelId(channelId: number): Promise<ChannelMember[]> {
        return this.memberModel.find({ channelId }).lean() as Promise<ChannelMember[]>;
    }
}
