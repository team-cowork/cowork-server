import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { loadTodoContent } from '../scripts/lib/todo-content.mjs';

const REPOSITORY_SOURCE_URL = 'https://github.com/team-cowork/cowork-server/blob/test-ref/';
const ITEM_PATH = 'items/21-performance/external-io-transaction-boundary.md';
const ITEM_ROUTE = '/todo/items/21-performance/external-io-transaction-boundary';

const createFixture = async (context, { body = '', files = {}, registryTarget = ITEM_PATH } = {}) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'cowork-todo-links-'));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const repositoryDirectory = path.join(directory, 'repository');
  const todoDirectory = path.join(repositoryDirectory, 'docs/todo');
  const sources = {
    'docs/todo/README.md': `# TODO

## 진행 중

- Performance: [Boundary](${registryTarget})

## 점검 스냅샷
`,
    [`docs/todo/${ITEM_PATH}`]: `# Boundary

- **우선순위**: 높음

## Details

${body}
`,
    ...files,
  };

  for (const [relativePath, source] of Object.entries(sources)) {
    const filename = path.join(repositoryDirectory, relativePath);
    await mkdir(path.dirname(filename), { recursive: true });
    await writeFile(filename, source);
  }

  return { directory, repositoryDirectory, todoDirectory };
};

const loadFixture = ({ repositoryDirectory, todoDirectory }) => loadTodoContent({
  repositoryDirectory,
  todoDirectory,
  repositorySourceUrl: REPOSITORY_SOURCE_URL,
});

const bodyHtml = (content) => content.documentsByRoute[ITEM_ROUTE].bodyHtml;

test('keeps generated TODO links on site and prefixes their heading anchors', async (context) => {
  const fixture = await createFixture(context, {
    body: `[Related](../31-performance/projection-incremental-resume.md?view=full#checkpoint)

[Registry](../../README.md)

[Details](#details)`,
    files: {
      'docs/todo/items/31-performance/projection-incremental-resume.md': `# Projection

- **우선순위**: 중간

## Checkpoint
`,
    },
  });

  const html = bodyHtml(await loadFixture(fixture));

  assert.ok(html.includes('href="/todo/items/31-performance/projection-incremental-resume?view=full#todo-checkpoint"'));
  assert.ok(html.includes('href="/todo"'));
  assert.ok(html.includes('href="#todo-details"'));
  assert.ok(html.includes('id="todo-details"'));
});

test('renders repository documentation links in inline and reference Markdown without TODO anchor prefixes', async (context) => {
  const fixture = await createFixture(context, {
    body: `[Connection pooling](../../../../cowork-project/docs/feign-hc5-pooling.md?plain=1#pooling)

[Projection policy][policy]

[policy]: ../../../../.claude/rules/kafka-projections.md#checkpoint`,
    files: {
      'cowork-project/docs/feign-hc5-pooling.md': '# Pooling\n',
      '.claude/rules/kafka-projections.md': '# Checkpoint\n',
    },
  });

  const html = bodyHtml(await loadFixture(fixture));

  assert.ok(html.includes(`href="${REPOSITORY_SOURCE_URL}cowork-project/docs/feign-hc5-pooling.md?plain=1#pooling"`));
  assert.ok(html.includes(`href="${REPOSITORY_SOURCE_URL}.claude/rules/kafka-projections.md#checkpoint"`));
  assert.ok(html.includes('target="_blank"'));
});

test('encodes repository file paths and links non-rendered TODO files to their source', async (context) => {
  const fixture = await createFixture(context, {
    body: `[Guide](../../../../cowork-project/docs/connection%20pool%20%EA%B0%80%EC%9D%B4%EB%93%9C.md#configuration)

[Data](../../fixtures/checkpoint.json?raw=1)`,
    files: {
      'cowork-project/docs/connection pool 가이드.md': '# Configuration\n',
      'docs/todo/fixtures/checkpoint.json': '{"offset":42}\n',
    },
  });

  const html = bodyHtml(await loadFixture(fixture));

  assert.ok(html.includes(`href="${REPOSITORY_SOURCE_URL}cowork-project/docs/connection%20pool%20%EA%B0%80%EC%9D%B4%EB%93%9C.md#configuration"`));
  assert.ok(html.includes(`href="${REPOSITORY_SOURCE_URL}docs/todo/fixtures/checkpoint.json?raw=1"`));
});

test('rejects missing repository link targets', async (context) => {
  const fixture = await createFixture(context, {
    body: '[Missing](../../../../cowork-project/docs/missing.md)',
  });

  await assert.rejects(() => loadFixture(fixture));
});

test('rejects links that traverse outside the repository even when the target exists', async (context) => {
  const fixture = await createFixture(context, {
    body: '[Outside](../../../../../outside.md)',
  });
  await writeFile(path.join(fixture.directory, 'outside.md'), '# Outside\n');

  await assert.rejects(() => loadFixture(fixture));
});

test('rejects repository links whose symlink target is outside the repository', async (context) => {
  const fixture = await createFixture(context, {
    body: '[Outside](../../../../linked-document.md)',
  });
  const outsidePath = path.join(fixture.directory, 'outside.md');
  await writeFile(outsidePath, '# Outside\n');
  await symlink(outsidePath, path.join(fixture.repositoryDirectory, 'linked-document.md'));

  await assert.rejects(() => loadFixture(fixture));
});

test('requires README registry entries to target generated TODO documents', async (context) => {
  const fixture = await createFixture(context, {
    registryTarget: '../../cowork-project/docs/feign-hc5-pooling.md',
    files: {
      'cowork-project/docs/feign-hc5-pooling.md': '# Boundary\n',
    },
  });

  await assert.rejects(() => loadFixture(fixture));
});
