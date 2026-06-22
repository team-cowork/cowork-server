import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
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

    async existsByTeam(teamId: number, userId: number): Promise<boolean> {
        const member = await this.memberModel.exists({ teamId, userId });
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
        return this.memberModel.find({ channelId }).lean();
    }

    async updateLastRead(channelId: number, userId: number, messageId: Types.ObjectId): Promise<void> {
        await this.memberModel.updateOne(
            { channelId, userId },
            { $set: { lastReadMessageId: messageId } },
        );
    }

    async findMembersByTeam(
        teamId: number,
        userId: number,
    ): Promise<Array<{ channelId: number; lastReadMessageId: Types.ObjectId | null }>> {
        const memberships = await this.memberModel
            .find({ teamId, userId }, { channelId: 1, lastReadMessageId: 1 })
            .lean();
        return memberships.map((m) => ({
            channelId: m.channelId,
            lastReadMessageId: (m.lastReadMessageId as Types.ObjectId | null | undefined) ?? null,
        }));
    }

    /**
     * 채널·사용자로 멤버십을 조회하고 팀 ID와 채널 타입을 반환합니다.
     * DM 채널 분기(차단 검사 등)가 필요한 메시지 전송 경로에서 사용합니다.
     *
     * @returns 멤버십 정보. 멤버가 아니면 `null`
     */
    async findMembership(
        channelId: number,
        userId: number,
    ): Promise<{ teamId: number | null; channelType: string } | null> {
        const member = await this.memberModel
            .findOne({ channelId, userId }, { teamId: 1, channelType: 1 })
            .lean();
        if (!member) return null;
        return {
            teamId: member.teamId ?? null,
            channelType: member.channelType ?? 'TEXT',
        };
    }

    /**
     * 사용자의 숨기지 않은 DM 멤버십 목록을 반환합니다.
     * DM 대화 목록 조회에 사용합니다.
     */
    async findDmMemberships(
        userId: number,
    ): Promise<Array<{ channelId: number; lastReadMessageId: Types.ObjectId | null }>> {
        const memberships = await this.memberModel
            .find(
                { userId, channelType: 'DM', isHidden: { $ne: true } },
                { channelId: 1, lastReadMessageId: 1 },
            )
            .lean();
        return memberships.map((m) => ({
            channelId: m.channelId,
            lastReadMessageId: (m.lastReadMessageId as Types.ObjectId | null | undefined) ?? null,
        }));
    }

    /**
     * 여러 DM 채널에서 본인을 제외한 상대 참여자를 조회합니다.
     *
     * @returns channelId → 상대 userId 매핑
     */
    async findOtherDmMembers(channelIds: number[], userId: number): Promise<Map<number, number>> {
        if (channelIds.length === 0) return new Map();
        const others = await this.memberModel
            .find({ channelId: { $in: channelIds }, userId: { $ne: userId } }, { channelId: 1, userId: 1 })
            .lean();
        return new Map(others.map((m) => [m.channelId, m.userId]));
    }

    /**
     * DM 대화 숨김 상태를 변경합니다.
     *
     * @returns 멤버십이 존재해 갱신 대상이 매칭되면 `true`
     */
    async setHidden(channelId: number, userId: number, isHidden: boolean): Promise<boolean> {
        const result = await this.memberModel.updateOne(
            { channelId, userId },
            { $set: { isHidden } },
        );
        return result.matchedCount > 0;
    }
}
