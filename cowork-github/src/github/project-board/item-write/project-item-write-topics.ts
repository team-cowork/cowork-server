export const PROJECT_ITEM_WRITE_COMMAND_TOPIC = 'github.project-item-write.command.v1';
export const PROJECT_ITEM_WRITE_RESULT_TOPIC = 'github.project-item-write.result.v1';

export enum ProjectItemWriteCommandType {
	MOVE_ITEM = 'MOVE_ITEM',
	REORDER_ITEM = 'REORDER_ITEM',
}
