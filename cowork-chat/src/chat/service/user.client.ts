import { Injectable } from '@nestjs/common';
import { UserProfileProjectionRepository } from '../repository/user-profile-projection.repository';

/** Kafka로 동기화된 로컬 사용자 프로필 projection 조회기. */
@Injectable()
export class UserClient {
    constructor(private readonly projectionRepository: UserProfileProjectionRepository) {}

    async getDisplayNames(userIds: number[]): Promise<Map<number, string>> {
        const uniqueUserIds = [...new Set(userIds)];
        if (uniqueUserIds.length === 0) return new Map();

        const profiles = await this.projectionRepository.findByUserIds(uniqueUserIds);
        return new Map(profiles.map((profile) => [
            profile.userId,
            profile.nickname && profile.nickname.length > 0 ? profile.nickname : profile.name,
        ]));
    }
}
