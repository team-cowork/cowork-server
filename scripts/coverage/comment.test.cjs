'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const publish = require('./comment.cjs');

function fixture({ comments = [], heads = ['head', 'head'], bases = ['base', 'base'], failure } = {}) {
  const calls = { reads: [], lists: [], creates: [], updates: [], deletes: [], warnings: [], info: [] };
  const context = {
    repo: { owner: 'owner', repo: 'repo' }, issue: { number: 7 },
    payload: { pull_request: { number: 7, head: { sha: 'head' }, base: { sha: 'base' } } },
  };
  const listComments = () => {};
  const github = {
    rest: {
      pulls: { get: async arguments_ => {
        const index = calls.reads.length;
        calls.reads.push(arguments_);
        return { data: { head: { sha: heads[index] ?? heads.at(-1) }, base: { sha: bases[index] ?? bases.at(-1) } } };
      } },
      issues: {
        listComments,
        createComment: async arguments_ => {
          calls.creates.push(arguments_);
          if (failure?.action === 'create') throw { status: failure.status };
          return { data: { id: 30 } };
        },
        updateComment: async arguments_ => {
          calls.updates.push(arguments_);
          if (failure?.action === 'update') throw { status: failure.status };
          return { data: { id: arguments_.comment_id } };
        },
        deleteComment: async arguments_ => {
          calls.deletes.push(arguments_);
          if (failure?.action === 'delete') throw { status: failure.status };
          return {};
        },
      },
    },
    paginate: async (method, arguments_) => {
      assert.equal(method, listComments);
      calls.lists.push(arguments_);
      return comments;
    },
  };
  const core = {
    warning: message => calls.warnings.push(message),
    info: message => calls.info.push(message),
  };
  return { calls, arguments: { github, context, core, body: `${publish.MARKER}\nNew coverage report` } };
}

function comment(id, login = 'github-actions[bot]', type = 'Bot', body = publish.MARKER) {
  return { id, user: { login, type }, body };
}

test('creates a new marked comment after checking PR head and base twice', async () => {
  const state = fixture();
  state.arguments.body = 'Report without a marker';
  assert.deepEqual(await publish(state.arguments), { status: 'created', id: 30 });
  assert.equal(state.calls.reads.length, 2);
  assert.equal(state.calls.creates[0].issue_number, 7);
  assert.equal(state.calls.creates[0].body, `${publish.MARKER}\nReport without a marker`);
  assert.equal(state.calls.lists[0].per_page, 100);
});

test('updates only GitHub Actions bot comments and cleans up its duplicates', async () => {
  const state = fixture({ comments: [
    comment(5, 'human', 'User'), comment(6, 'other[bot]'), comment(7, 'github-actions[bot]', 'User'),
    comment(20), comment(10), comment(1, 'github-actions[bot]', 'Bot', 'Unrelated bot comment'),
  ] });
  assert.deepEqual(await publish(state.arguments), { status: 'updated', id: 10 });
  assert.equal(state.calls.updates[0].comment_id, 10);
  assert.deepEqual(state.calls.deletes.map(value => value.comment_id), [20]);
  assert.equal(state.calls.creates.length, 0);
});

test('does not overwrite user or other bot comments containing the marker', async () => {
  const state = fixture({ comments: [comment(1, 'human', 'User'), comment(2, 'other[bot]')] });
  assert.equal((await publish(state.arguments)).status, 'created');
  assert.equal(state.calls.updates.length, 0);
  assert.equal(state.calls.deletes.length, 0);
});

test('skips a stale head before even listing comments', async () => {
  const state = fixture({ heads: ['new-head'] });
  assert.equal((await publish(state.arguments)).status, 'stale');
  assert.equal(state.calls.lists.length, 0);
  assert.equal(state.calls.creates.length, 0);
});

test('checks base changes as well as head changes', async () => {
  const state = fixture({ bases: ['new-base'] });
  assert.equal((await publish(state.arguments)).status, 'stale');
  assert.equal(state.calls.creates.length, 0);
});

test('rechecks freshness after pagination before updating or deleting', async () => {
  const state = fixture({ heads: ['head', 'new-head'], comments: [comment(1), comment(2)] });
  assert.equal((await publish(state.arguments)).status, 'stale');
  assert.equal(state.calls.lists.length, 1);
  assert.equal(state.calls.updates.length, 0);
  assert.equal(state.calls.deletes.length, 0);
});

test('fork permission denial produces a warning instead of failing the job', async () => {
  for (const action of ['create', 'update']) {
    const state = fixture({ comments: action === 'update' ? [comment(1)] : [], failure: { action, status: 403 } });
    assert.equal((await publish(state.arguments)).status, 'forbidden');
    assert.match(state.calls.warnings[0], /403.*Fork PR.*summary.*artifacts/);
  }
});

test('cleanup permission denial preserves successful publication', async () => {
  const state = fixture({ comments: [comment(1), comment(2)], failure: { action: 'delete', status: 403 } });
  assert.deepEqual(await publish(state.arguments), { status: 'updated', id: 1 });
  assert.match(state.calls.warnings[0], /published.*cleanup.*403/);
});

test('unexpected API errors are not hidden as fork permission failures', async () => {
  const state = fixture({ failure: { action: 'create', status: 500 } });
  await assert.rejects(() => publish(state.arguments), { status: 500 });
  assert.equal(state.calls.warnings.length, 0);
});

test('missing PR metadata does not write a comment', async () => {
  const state = fixture();
  state.arguments.context.payload = {};
  assert.equal((await publish(state.arguments)).status, 'skipped');
  assert.equal(state.calls.reads.length, 0);
});
