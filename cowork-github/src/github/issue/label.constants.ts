import type { CreateLabelPayload } from '../client/github-api.client';

export const COWORK_DEFAULT_LABELS: CreateLabelPayload[] = [
  { name: 'blocked:차단됨', color: 'b60205', description: '진행이 차단된 이슈' },
  { name: 'bug:버그', color: 'd73a4a', description: '버그 또는 오작동' },
  {
    name: 'documentation:문서화',
    color: '0075ca',
    description: '문서 작성 또는 수정',
  },
  { name: 'duplicate:중복', color: 'cfd3d7', description: '중복 이슈' },
  {
    name: 'enhancement:개선작업',
    color: 'a2eeef',
    description: '기능 추가 또는 개선',
  },
  {
    name: 'GFI:첫 기여 추천',
    color: '7057ff',
    description: '첫 기여자에게 추천할 만한 작업',
  },
  {
    name: 'help wanted:도움 필요',
    color: '008672',
    description: '도움이나 추가 논의가 필요한 이슈',
  },
  { name: 'invalid:무효한', color: 'e4e669', description: '유효하지 않은 이슈' },
  { name: 'release:릴리즈', color: '5319e7', description: '릴리즈 관련 작업' },
  {
    name: 'waiting for review:검토 대기',
    color: 'fbca04',
    description: '검토 대기 상태',
  },
];

export const BUG_KEYWORDS = [
  'error',
  'bug',
  'fail',
  'exception',
  'crash',
  '500',
  '오류',
  '에러',
  '실패',
  '안됨',
  '안돼',
  '안되',
  '이상',
  '문제',
  '오작동',
  '먹통',
] as const;

export const ENHANCEMENT_KEYWORDS = [
  'feature',
  'enhancement',
  'support',
  '추가',
  '개선',
  '요청',
  '기능',
] as const;

export const QUESTION_KEYWORDS = [
  'question',
  'how',
  '문의',
  '질문',
  '어떻게',
  '가능',
] as const;

export const BUG_LABEL_CANDIDATES = ['bug:버그', 'bug'] as const;
export const ENHANCEMENT_LABEL_CANDIDATES = [
  'enhancement',
  'enhancement:개선작업',
] as const;
export const QUESTION_LABEL_CANDIDATES = [
  'question',
  'help wanted:도움 필요',
] as const;
export const FALLBACK_LABEL_CANDIDATES = [
  'help wanted:도움 필요',
  'waiting for review:검토 대기',
  'question',
  'enhancement:개선작업',
  'bug:버그',
] as const;
