ALTER TABLE tb_github_project_item_write_operations
    DROP CHECK ck_tb_github_project_item_write_operations_command_type,
    MODIFY COLUMN command_type VARCHAR(20) NOT NULL COMMENT 'GitHub Project item write command type',
    ADD CONSTRAINT ck_tb_github_project_item_write_operations_command_type CHECK (
        command_type IN ('MOVE_ITEM', 'REORDER_ITEM', 'SET_FIELD', 'CLEAR_FIELD', 'CREATE_DRAFT',
                         'UPDATE_DRAFT', 'ADD_ITEM', 'REMOVE_ITEM', 'CONVERT_DRAFT', 'SET_ASSIGNEES')
    );
