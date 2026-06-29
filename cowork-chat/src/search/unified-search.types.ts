import { Field, Int, ObjectType } from '@nestjs/graphql';

@ObjectType({ description: '통합 검색 — 메시지 검색 결과 단건' })
export class SearchMessageItem {
    @Field({ description: '메시지 MongoDB ObjectId' })
    messageId!: string;

    @Field(() => Int, { description: '메시지가 속한 채널 ID' })
    channelId!: number;

    @Field(() => Int, { description: '작성자 유저 ID' })
    authorId!: number;

    @Field({ description: '메시지 본문' })
    content!: string;

    @Field(() => [String], { description: '매칭 키워드가 <em>...</em>으로 감싸진 snippet 배열' })
    highlight!: string[];

    @Field({ description: 'TEXT | FILE | SYSTEM' })
    type!: string;

    @Field({ description: '첨부파일 포함 여부' })
    hasAttachments!: boolean;

    @Field({ description: '고정 메시지 여부' })
    isPinned!: boolean;

    @Field({ description: 'ISO 8601 생성 일시' })
    createdAt!: string;
}

@ObjectType({ description: '통합 검색 — 채널 검색 결과 단건' })
export class SearchChannelItem {
    @Field(() => Int, { description: '채널 ID' })
    id!: number;

    @Field({ description: '채널명' })
    name!: string;

    @Field({ description: 'GENERAL | DM | PROJECT 등' })
    type!: string;

    @Field({ description: 'DEFAULT | FILE_SHARE 등' })
    viewType!: string;

    @Field({ nullable: true, description: '채널 설명 (없으면 null)' })
    description?: string | null;

    @Field({ description: '비공개 채널 여부' })
    isPrivate!: boolean;
}

@ObjectType({ description: '통합 검색 결과 — 메시지(ES)와 채널(channel-service)을 병렬 조회해 반환' })
export class UnifiedSearchResult {
    @Field(() => [SearchMessageItem], { description: 'Elasticsearch 메시지 검색 결과 (최신순)' })
    messages!: SearchMessageItem[];

    @Field({ nullable: true, description: '다음 페이지 커서. 마지막 페이지이면 null' })
    messageNextCursor?: string | null;

    @Field(() => [SearchChannelItem], { description: '채널명 키워드 검색 결과' })
    channels!: SearchChannelItem[];
}
