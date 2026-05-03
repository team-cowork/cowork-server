import type { CreateLabelPayload } from '../client/github-api.client';

export const COWORK_LABELS: CreateLabelPayload[] = [
  {
    name: 'bug:버그',
    color: 'd73a4a',
    description: '무언가 작동하지 않습니다',
  },
  {
    name: 'enhancement:개선작업',
    color: 'a2eeef',
    description: '새 기능 또는 기존 코드 개선에 관한 내용입니다',
  },
  {
    name: 'question:질문',
    color: 'd876e3',
    description: '질문 또는 문의사항이 있습니다',
  },
  {
    name: 'help wanted:도움 필요',
    color: '008672',
    description: '추가적인 지원이 필요합니다',
  },
  {
    name: 'blocked:차단됨',
    color: 'b60205',
    description: '해당 작업은 다른 작업에 의해 차단되었습니다',
  },
  {
    name: 'duplicate:중복',
    color: 'cfd3d7',
    description: '이 Issue 또는 Pull Request가 이미 존재합니다',
  },
  {
    name: 'documentation:문서화',
    color: '0075ca',
    description: '문서에 대한 개선 또는 추가사항이 있습니다',
  },
  {
    name: 'release:릴리즈',
    color: '5319e7',
    description: '프로젝트를 배포합니다',
  },
  {
    name: 'waiting for review:검토 대기',
    color: 'fbca04',
    description: '확인을 대기하고 있습니다',
  },
  {
    name: 'GFI:첫 기여 추천',
    color: '7057ff',
    description: '첫 기여로 훌륭한 Issue입니다',
  },
];

export const COWORK_FALLBACK_LABEL = 'help wanted:도움 필요' as const;

export const BUG_LABEL = 'bug:버그' as const;
export const ENHANCEMENT_LABEL = 'enhancement:개선작업' as const;
export const QUESTION_LABEL = 'question:질문' as const;

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
