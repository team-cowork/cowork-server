-- 팀 로드맵 목록은 owner_team_id와 category로 거른다(findListByOwnerTeamId).
-- (owner_team_id, category) 인덱스가 owner_team_id 단독 조건도 처리하므로 기존 단일 컬럼 인덱스를 대체한다.
ALTER TABLE tb_roadmaps
    DROP INDEX idx_tb_roadmaps_owner_team_id,
    ADD INDEX idx_tb_roadmaps_owner_team_id_category (owner_team_id, category);
