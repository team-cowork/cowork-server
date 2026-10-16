-- preference.team-role.state.v2 full snapshot이 권한 객체로 다시 채운다. 이전 topic의 배열 형식은 읽지 않는다.
UPDATE tb_team_role_projections
SET permissions_json = '{}'
WHERE permissions_json LIKE '[%';
