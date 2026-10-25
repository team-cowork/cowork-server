ALTER TABLE tb_preference_event_outbox
    DROP CONSTRAINT ck_tb_preference_event_outbox_topic;

ALTER TABLE tb_preference_event_outbox
    ADD CONSTRAINT ck_tb_preference_event_outbox_topic CHECK (topic IN (
        'preference.channel-notification.changed',
        'preference.team-role.changed',
        'preference.team-role.command-result',
        'preference.team-role.state.v2',
        'preference.team-role.result.v2',
        'preference.github-repo.setting.state',
        'preference.github-repo.setting.result',
        'preference.channel-role-policy.changed',
        'preference.channel-role-policy.command-result',
        'preference.channel-role-policy.state.v2',
        'preference.channel-role-policy.result.v2',
        'preference.status.changed',
        'preference.team.setting.changed',
        'preference.project-access.state.v1',
        'preference.account-setting.state.v1',
        'preference.team-setting.state.v1',
        'preference.text-channel-setting.state.v1',
        'preference.voice-channel-setting.state.v1'
    ));
