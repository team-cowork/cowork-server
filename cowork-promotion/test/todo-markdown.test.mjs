import assert from 'node:assert/strict';
import test from 'node:test';

import { renderTodoMarkdown } from '../scripts/lib/markdown.mjs';

const resolver = ({ pathname }) => {
  if (pathname === './other.md') {
    return '/todo/items/01-fixture/other';
  }

  throw new Error(`unexpected fixture link: ${pathname}`);
};

test('renders sanitized GFM, metadata, stable headings, and rewritten links', async () => {
  const document = await renderTodoMarkdown({
    sourcePath: 'items/01-fixture/example.md',
    resolveLink: resolver,
    source: `# 문서 제목

- **서비스**: cowork-test
- **우선순위**: 🟢 낮음
- **관련 작업**: [링크](./other.md)

<script>alert('removed')</script>

## 반복 제목

| 열 | 값 |
|---|---|
| a | b |

## 반복 제목

- [x] 완료

\`\`\`js
const value = true;
\`\`\`
`,
  });

  assert.equal(document.title, '문서 제목');
  assert.equal(document.priority, 'low');
  assert.equal(document.priorityLabel, '낮음');
  assert.match(document.metadata[2].html, /href="\/todo\/items\/01-fixture\/other"/);
  assert.doesNotMatch(document.bodyHtml, /script|alert/);
  assert.match(document.bodyHtml, /todo-document__table-scroll/);
  assert.match(document.bodyHtml, /language-js/);
  assert.match(document.bodyHtml, /type="checkbox"/);
  assert.deepEqual(document.toc.map(({ id }) => id), [
    'todo-반복-제목',
    'todo-반복-제목-1',
  ]);
});

test('rejects unsafe schemes and images', async () => {
  await assert.rejects(
    renderTodoMarkdown({
      sourcePath: 'items/01-fixture/unsafe.md',
      resolveLink: resolver,
      source: '# Unsafe\n\n[click](javascript:alert(1))',
    }),
    /unsafe link protocol/,
  );

  await assert.rejects(
    renderTodoMarkdown({
      sourcePath: 'items/01-fixture/image.md',
      resolveLink: resolver,
      source: '# Image\n\n![alt](image.png)',
    }),
    /images are not supported/,
  );
});
