DELETE
FROM tb_account_team_roles atr
WHERE NOT EXISTS (
    SELECT 1
    FROM tb_team_role_definitions trd
    WHERE trd.id = atr.role_id
);

ALTER TABLE tb_account_team_roles
    ADD CONSTRAINT fk_tb_account_team_roles_role
        FOREIGN KEY (role_id)
            REFERENCES tb_team_role_definitions (id)
            ON DELETE CASCADE;
