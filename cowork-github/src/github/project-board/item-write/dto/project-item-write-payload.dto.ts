import {Type as type} from 'class-transformer';
import {
	ArrayMaxSize as arrayMaxSize,
	ArrayUnique as arrayUnique,
	IsArray as isArray,
	IsBoolean as isBoolean,
	IsDateString as isDateString,
	IsInt as isInt,
	IsNotEmpty as isNotEmpty,
	IsNumber as isNumber,
	IsObject as isObject,
	IsOptional as isOptional,
	IsString as isString,
	Matches as matches,
	MaxLength as maxLength,
	Min as min,
	ValidateIf as validateIf,
	ValidateNested as validateNested,
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

export class FieldValueDto {
	@isOptional()
	@isString()
	text?: string;

	@isOptional()
	@isNumber({allowNaN: false, allowInfinity: false})
	number?: number;

	@isOptional()
	@matches(/^\d{4}-\d{2}-\d{2}$/v)
	@isDateString({strict: true})
	date?: string;

	@isOptional()
	@isString()
	@isNotEmpty()
	singleSelectOptionId?: string;

	@isOptional()
	@isString()
	@isNotEmpty()
	iterationId?: string;
}

export class ClearFieldPayloadDto {
	@isString()
	@isNotEmpty()
	fieldId: string;
}

export class SetFieldPayloadDto extends ClearFieldPayloadDto {
	@isObject()
	@validateNested()
	@type(() => FieldValueDto)
	value: FieldValueDto;
}

export class CreateDraftPayloadDto {
	@isString()
	@matches(/\S/v)
	@maxLength(256)
	title: string;

	@isString()
	@maxLength(65_536)
	body: string;
}

export class UpdateDraftPayloadDto {
	@isOptional()
	@isString()
	@matches(/\S/v)
	@maxLength(256)
	title?: Nullable<string>;

	@isOptional()
	@isString()
	@maxLength(65_536)
	body?: Nullable<string>;
}

export class ConvertDraftPayloadDto {
	@isString()
	@matches(/^[\w\-.]+$/v)
	repo: string;
}

export class AddItemPayloadDto extends ConvertDraftPayloadDto {
	@isInt()
	@min(1)
	number: number;
}

export class SetAssigneesPayloadDto extends ConvertDraftPayloadDto {
	@isArray()
	@arrayMaxSize(10)
	@arrayUnique((name: unknown) => typeof name === 'string' ? name.toLowerCase() : name)
	@isString({each: true})
	@matches(/^\S+$/v, {each: true})
	assignees: string[];
}
