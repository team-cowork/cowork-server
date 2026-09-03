import assert from 'node:assert/strict';
import test from 'node:test';

import { parsePriority } from '../scripts/lib/markdown.mjs';
import { sortTodoItems } from '../scripts/lib/todo-content.mjs';

for (const { label, marker, priority } of [
  { label: '높음', marker: '🔴', priority: 'high' },
  { label: '중간', marker: '🟠', priority: 'medium' },
  { label: '낮음', marker: '🟢', priority: 'low' },
]) {
  test(`classifies ${label} TODO priority with or without its marker`, () => {
    assert.equal(parsePriority(label), priority);
    assert.equal(parsePriority(`${marker} ${label}`), priority);
  });
}

test('treats missing or unsupported TODO priorities as unspecified', () => {
  for (const value of [undefined, null, '', '미지정', '긴급']) {
    assert.equal(parsePriority(value), 'unknown');
  }
});

test('orders TODOs by high, medium, low, then unspecified priority before registry order', () => {
  const items = [
    { route: '/todo/items/unknown', priority: 'unknown', sourceOrder: 0 },
    { route: '/todo/items/low', priority: 'low', sourceOrder: 1 },
    { route: '/todo/items/medium', priority: 'medium', sourceOrder: 2 },
    { route: '/todo/items/high', priority: 'high', sourceOrder: 3 },
  ];

  assert.deepEqual(sortTodoItems(items).map(({ route }) => route), [
    '/todo/items/high',
    '/todo/items/medium',
    '/todo/items/low',
    '/todo/items/unknown',
  ]);
});

test('places missing and unsupported priorities after all known priorities', () => {
  const items = [
    { route: '/todo/items/missing', sourceOrder: 0 },
    { route: '/todo/items/unsupported', priority: 'urgent', sourceOrder: 1 },
    { route: '/todo/items/low', priority: 'low', sourceOrder: 2 },
  ];

  assert.deepEqual(sortTodoItems(items).map(({ route }) => route), [
    '/todo/items/low',
    '/todo/items/missing',
    '/todo/items/unsupported',
  ]);
});

test('uses registry order for TODOs with the same priority', () => {
  const items = [
    { route: '/todo/items/a', priority: 'high', sourceOrder: 2 },
    { route: '/todo/items/z', priority: 'high', sourceOrder: 0 },
    { route: '/todo/items/m', priority: 'high', sourceOrder: 1 },
  ];

  assert.deepEqual(sortTodoItems(items).map(({ route }) => route), [
    '/todo/items/z',
    '/todo/items/m',
    '/todo/items/a',
  ]);
});
