import {
	ArgsType as argsType,
	Field as field,
	Float,
	Int,
	ObjectType as objectType,
} from '@nestjs/graphql';
import {Allow as allow} from 'class-validator';
import {type Nullable} from '../common/type/nullable.js';

@objectType()
export class SearchWebhookAuthor {
	@field(() => Float, {description: '웹훅 ID (JavaScript safe integer 범위의 양의 정수)'}) id!: number;
	@field() name!: string;
	@field(() => String, {nullable: true}) avatarUrl!: Nullable<string>;
}

@objectType({description: '통합 검색 — 메시지 검색 결과 단건'})
export class SearchMessageItem {
	@field({description: '메시지 MongoDB ObjectId'})
	messageId!: string;

	@field(() => Int, {description: '메시지가 속한 채널 ID'})
	channelId!: number;

	@field(() => Int, {description: '작성자 유저 ID', nullable: true})
	authorId!: Nullable<number>;

	@field(() => SearchWebhookAuthor, {nullable: true})
	webhook?: SearchWebhookAuthor;

	@field({description: '메시지 본문'})
	content!: string;

	@field(() => [String], {description: '매칭 키워드가 <em>...</em>으로 감싸진 snippet 배열'})
	highlight!: string[];

	@field({description: 'TEXT | FILE | SYSTEM'})
	type!: string;

	@field({description: '첨부파일 포함 여부'})
	hasAttachments!: boolean;

	@field({description: '고정 메시지 여부'})
	isPinned!: boolean;

	@field({description: 'ISO 8601 생성 일시'})
	createdAt!: string;
}

@objectType({description: '통합 검색 — 채널 검색 결과 단건'})
export class SearchChannelItem {
	@field(() => Int, {description: '채널 ID'})
	id!: number;

	@field({description: '채널명'})
	name!: string;

	@field({description: 'GENERAL | DM | PROJECT 등'})
	type!: string;

	@field({description: 'DEFAULT | FILE_SHARE 등'})
	viewType!: string;

	@field(() => String, {nullable: true, description: '채널 설명 (없으면 null)'})
	description?: Nullable<string>;

	@field({description: '비공개 채널 여부'})
	isPrivate!: boolean;
}

@objectType({description: '통합 검색 결과 — 메시지(ES)와 Kafka 동기화 채널 projection을 병렬 조회해 반환'})
export class UnifiedSearchResult {
	@field(() => [SearchMessageItem], {description: 'Elasticsearch 메시지 검색 결과 (최신순)'})
	messages!: SearchMessageItem[];

	@field(() => String, {nullable: true, description: '다음 페이지 커서. 마지막 페이지이면 null'})
	messageNextCursor?: Nullable<string>;

	@field(() => [SearchChannelItem], {
		description: '공개 채널과 요청자가 가입한 비공개 채널의 채널명 키워드 검색 결과',
	})
	channels!: SearchChannelItem[];
}

/**
`unifiedSearch` 쿼리 인자. 전역 ValidationPipe의 whitelist가 값을 지우지 않도록 `allow()`만 붙이고,
실제 검증은 resolver가 `SearchTeamMessagesDto`로 한다.
*/
@argsType()
export class UnifiedSearchArgs {
	@field(() => Int)
	@allow()
	teamId!: number;

	@field()
	@allow()
	q!: string;

	@field(() => Int, {nullable: true})
	@allow()
	channelId?: number;

	@field(() => Int, {nullable: true})
	@allow()
	authorId?: number;

	@field({nullable: true})
	@allow()
	type?: string;

	@field({nullable: true})
	@allow()
	hasFile?: boolean;

	@field({nullable: true})
	@allow()
	before?: string;

	@field(() => Int, {nullable: true})
	@allow()
	limit?: number;
}
