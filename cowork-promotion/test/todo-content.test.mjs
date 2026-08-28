import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  loadTodoContent,
  normalizeSearchText,
} from '../scripts/lib/todo-content.mjs';

const fixtureDirectory = fileURLToPath(new URL('./fixtures/todo/', import.meta.url));

test('builds the active registry while preserving orphan routes and priority order', async () => {
  const warnings = [];
  const content = await loadTodoContent({
    todoDirectory: fixtureDirectory,
    onWarning: (warning) => warnings.push(warning),
  });

  assert.deepEqual(content.activeItems.map(({ title }) => title), [
    '높은 작업',
    '낮은 작업',
  ]);
  assert.equal(content.activeItems[1].priority, 'low');
  assert.equal(content.activeItems[0].searchText, '높은 작업 cowork-high fixture');
  assert.equal(content.documentsByRoute['/todo/items/01-fixture/orphan'].active, false);
  assert.equal(content.snapshots[0].displayDate, '2026.01.02');
  assert.equal(content.snapshots[0].description, 'fixture 점검');
  assert.match(content.snapshots[0].bodyHtml, /href="\/todo"/);
  assert.equal(warnings.length, 1);
});

test('normalizes search text with NFKC, lowercase, and collapsed whitespace', () => {
  assert.equal(normalizeSearchText('  ＡBC\n  COWORK  '), 'abc cowork');
});
