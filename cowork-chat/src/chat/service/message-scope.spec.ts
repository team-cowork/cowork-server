import { resolveMessageScope } from './message-scope';
import { ChannelProjectionView } from '../repository/channel-projection.repository';

function channel(overrides: Partial<ChannelProjectionView>): ChannelProjectionView {
    return {
        channelId: 1, teamId: 10, projectId: null, name: '', type: 'TEXT',
        viewType: '', description: null, isPrivate: false, position: 0, ...overrides,
    };
}

describe('resolveMessageScope', () => {
    it('팀 채널과 프로젝트 채널의 범위를 채널 projection에서 결정한다', () => {
        expect(resolveMessageScope(channel({}))).toEqual({ teamId: 10, projectId: null });
        expect(resolveMessageScope(channel({ projectId: 20 }))).toEqual({ teamId: 10, projectId: 20 });
    });

    it('DM의 범위는 항상 null이고 잘못된 채널 projection은 거부한다', () => {
        expect(resolveMessageScope(channel({ type: 'DM', teamId: null }))).toEqual({ teamId: null, projectId: null });
        expect(resolveMessageScope(channel({ type: 'DM', projectId: 20 }))).toBeNull();
        expect(resolveMessageScope(channel({ teamId: null }))).toBeNull();
    });
});
