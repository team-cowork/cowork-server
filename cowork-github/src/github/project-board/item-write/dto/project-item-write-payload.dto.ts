import {
	IsBoolean as isBoolean,
	IsNotEmpty as isNotEmpty,
	IsOptional as isOptional,
	IsString as isString,
	ValidateIf as validateIf,
} from 'class-validator';
import {type Nullable} from '../../../common/nullable.js';

const isNotNull = (_object: unknown, value: unknown): boolean => value !== null;

/**
 카드를 다른 컬럼으로 옮긴다. 컬럼 field의 값을 `singleSelectOptionId`나 `iterationId`로 바꾸고, 둘 다 null이면 값을 지운다
 이때 카드는 값이 없는 컬럼으로 간다. `reposition`이 true이면 이어서 `afterItemId` 뒤로 위치를 옮긴다.
 */
export class MoveItemPayloadDto {
	@isString()
	@isNotEmpty()
	fieldId: string;

	@isOptional()
	@validateIf(isNotNull)
	@isString()
	@isNotEmpty()
	singleSelectOptionId?: Nullable<string>;

	@isOptional()
	@validateIf(isNotNull)
	@isString()
	@isNotEmpty()
	iterationId?: Nullable<string>;

	@isBoolean()
	reposition: boolean;

	@isOptional()
	@validateIf(isNotNull)
	@isString()
	@isNotEmpty()
	afterItemId?: Nullable<string>;
}

/**
 카드 위치를 `afterItemId` 바로 뒤로 옮긴다. null이면 Project 전체의 맨 앞이다.
 */
export class ReorderItemPayloadDto {
	@isOptional()
	@validateIf(isNotNull)
	@isString()
	@isNotEmpty()
	afterItemId?: Nullable<string>;
}
