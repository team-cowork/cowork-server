ALTER TABLE tb_project_access_states
    ADD COLUMN can_board_read BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN can_board_write BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN tb_project_access_states.can_board_read IS
    '프로젝트에 연결된 GitHub Project 보드 보기 권한. 시작 snapshot이 모든 프로젝트를 다시 계산해 기존 행을 갱신한다';
COMMENT ON COLUMN tb_project_access_states.can_board_write IS
    '프로젝트에 연결된 GitHub Project 보드의 카드 이동 권한. 보드 보기 권한이 있을 때만 참이다';

-- TODO(2026-11-17T01:19:32Z): Remove the one-time backfill below after every environment has applied it.
-- It copies the existing project read/write grants to the new board read/write keys, so board access
-- matches project access at release time. Keep the column changes above. Removing statements from an
-- applied migration changes its checksum, so run `flyway repair` in the same change that removes them.

UPDATE tb_team_role_definitions
SET permissions = permissions || jsonb_build_object(
        'project_board_read', COALESCE((permissions ->> 'project_read')::BOOLEAN, FALSE),
        'project_board_write', COALESCE((permissions ->> 'project_write')::BOOLEAN, FALSE)
    ),
    updated_at = now()
WHERE jsonb_typeof(permissions) = 'object'
  AND NOT permissions ? 'project_board_read'
  AND NOT permissions ? 'project_board_write';

UPDATE project_role_definition
SET permissions = permissions
        || CASE WHEN permissions ? 'read' THEN jsonb_build_object('board_read', permissions -> 'read') ELSE '{}'::JSONB END
        || CASE WHEN permissions ? 'write' THEN jsonb_build_object('board_write', permissions -> 'write') ELSE '{}'::JSONB END,
    updated_at = now()
WHERE (permissions ? 'read' OR permissions ? 'write')
  AND NOT permissions ? 'board_read'
  AND NOT permissions ? 'board_write';
