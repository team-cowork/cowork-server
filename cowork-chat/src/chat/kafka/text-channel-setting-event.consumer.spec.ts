import {isTextChannelSettingEvent} from './text-channel-setting-event.consumer';

const event = (overrides: Record<string, unknown> = {}) => ({
    schemaVersion: 1,
    eventType: 'UPSERT',
    channelId: 11,
    slowmodeSeconds: 30,
    occurredAt: '2026-10-23T01:02:03.000001Z',
    snapshot: false,
    ...overrides,
});

describe('isTextChannelSettingEvent', () => {
    it('슬로우 모드 간격이 허용 범위인 UPSERT를 받는다', () => {
        expect(isTextChannelSettingEvent(event())).toBe(true);
        expect(isTextChannelSettingEvent(event({slowmodeSeconds: 0}))).toBe(true);
    });

    it('범위를 벗어난 간격이나 다른 계약의 레코드는 거부한다', () => {
        for (const overrides of [
            {slowmodeSeconds: -1},
            {slowmodeSeconds: 21_601},
            {slowmodeSeconds: 1.5},
            {schemaVersion: 2},
            {eventType: 'DELETE'},
            {channelId: 0},
            {occurredAt: 'yesterday'},
        ]) {
            expect(isTextChannelSettingEvent(event(overrides))).toBe(false);
        }
    });
});
