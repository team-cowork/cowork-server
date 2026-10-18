import {PROJECTION_STREAMS, isDeclaredPredecessor} from './projection-readiness.service';

describe('isDeclaredPredecessor', () => {
    const {userProfile} = PROJECTION_STREAMS;
    const previous = {topic: 'user.profile.event', groupId: 'cowork-chat-user-profile-event-v1-projection'};

    it('선언한 이전 정체성으로 저장된 dataset은 자동 rebuild 대상이다', () => {
        expect(isDeclaredPredecessor(userProfile, previous)).toBe(true);
    });

    it('그 밖의 정체성 변경은 명시적인 rebuild 요청을 기다린다', () => {
        expect(isDeclaredPredecessor(userProfile, {...previous, groupId: 'cowork-chat-user-profile-event-v0-projection'})).toBe(false);
        expect(isDeclaredPredecessor(userProfile, {...previous, topic: 'user.profile.event.v3'})).toBe(false);
        expect(isDeclaredPredecessor(PROJECTION_STREAMS.channel, previous)).toBe(false);
    });
});
