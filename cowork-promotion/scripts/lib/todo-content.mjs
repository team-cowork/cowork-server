import { realpathSync, statSync } from 'node:fs';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';

import { toString } from 'mdast-util-to-string';

import {
  parseMarkdown,
  priorityDetails,
  renderTodoMarkdown,
} from './markdown.mjs';

const README_PATH = 'README.md';
const PRIORITY_UNKNOWN_ORDER = 4;

const asPosixPath = (value) => value.split(path.sep).join('/');

export const normalizeSearchText = (value) => String(value ?? '')
  .normalize('NFKC')
  .toLocaleLowerCase()
  .replace(/\s+/g, ' ')
  .trim();

export const todoRouteForSource = (sourcePath) => {
  const normalized = asPosixPath(sourcePath);

  if (/^items\/(?:[^/]+\/)+[^/]+\.md$/.test(normalized)) {
    return `/todo/${normalized.slice(0, -3)}`;
  }

  const snapshotMatch = /^(\d{8})_TODO\.md$/.exec(normalized);

  if (snapshotMatch) {
    return `/todo/history/${snapshotMatch[1]}`;
  }

  if (normalized === README_PATH) {
    return '/todo';
  }

  return null;
};

const documentKind = (sourcePath) => sourcePath.startsWith('items/') ? 'item' : 'snapshot';

const documentId = (sourcePath) => sourcePath.slice(0, -3);

const deriveCategory = (sourcePath) => {
  const directory = sourcePath.split('/')[1] ?? '';
  return directory.replace(/^\d+-/, '');
};

const displayDate = (date) => `${date.slice(0, 4)}.${date.slice(4, 6)}.${date.slice(6, 8)}`;

const collectMarkdownFiles = async (directory, relativeDirectory = '') => {
  const absoluteDirectory = path.join(directory, relativeDirectory);
  const entries = await readdir(absoluteDirectory, { withFileTypes: true });
  const files = [];

  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const relativePath = asPosixPath(path.join(relativeDirectory, entry.name));

    if (entry.isSymbolicLink()) {
      throw new Error(`docs/todo must not contain symbolic links: ${relativePath}`);
    }

    if (entry.isDirectory()) {
      files.push(...await collectMarkdownFiles(directory, relativePath));
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      files.push(relativePath);
    }
  }

  return files;
};

const findSectionNodes = (tree, title, sourcePath) => {
  const start = tree.children.findIndex(
    (node) => node.type === 'heading' && node.depth === 2 && toString(node).trim() === title,
  );

  if (start === -1) {
    throw new Error(`${sourcePath}: missing "${title}" section`);
  }

  let end = tree.children.length;

  for (let index = start + 1; index < tree.children.length; index += 1) {
    const node = tree.children[index];

    if (node.type === 'heading' && node.depth <= 2) {
      end = index;
      break;
    }
  }

  return tree.children.slice(start + 1, end);
};

const activeLinksIn = (node, insideDelete = false, result = []) => {
  const deleted = insideDelete || node.type === 'delete';

  if (node.type === 'link' && !deleted) {
    result.push(node);
  }

  if (Array.isArray(node.children)) {
    for (const child of node.children) {
      activeLinksIn(child, deleted, result);
    }
  }

  return result;
};

const listItemsIn = (nodes) => nodes
  .filter((node) => node.type === 'list')
  .flatMap((list) => list.children);

const directParagraph = (listItem) => listItem.children.find((node) => node.type === 'paragraph');

const textBeforeLink = (listItem, link) => {
  const paragraph = directParagraph(listItem);

  if (!paragraph) {
    return '';
  }

  const linkIndex = paragraph.children.indexOf(link);
  return linkIndex === -1
    ? ''
    : toString({ type: 'root', children: paragraph.children.slice(0, linkIndex) });
};

const textAfterLink = (listItem, link) => {
  const paragraph = directParagraph(listItem);

  if (!paragraph) {
    return '';
  }

  const linkIndex = paragraph.children.indexOf(link);
  return linkIndex === -1
    ? ''
    : toString({ type: 'root', children: paragraph.children.slice(linkIndex + 1) });
};

const parseRegistrySection = ({ nodes, kind, sourcePath }) => {
  const entries = [];

  for (const [sourceOrder, listItem] of listItemsIn(nodes).entries()) {
    const links = activeLinksIn(listItem);

    // A fully struck-through row is a completed item and deliberately omitted.
    if (links.length === 0) {
      continue;
    }

    if (links.length !== 1) {
      throw new Error(`${sourcePath}: each ${kind} registry row must contain exactly one active link`);
    }

    const link = links[0];
    const title = toString(link).trim();

    if (!title || !link.url) {
      throw new Error(`${sourcePath}: ${kind} registry links need text and a target`);
    }

    if (kind === 'item') {
      const category = textBeforeLink(listItem, link).replace(/\s*:\s*$/, '').trim();

      if (!category) {
        throw new Error(`${sourcePath}: active item "${title}" is missing its category`);
      }

      entries.push({ kind, title, category, href: link.url, sourceOrder });
      continue;
    }

    const description = textAfterLink(listItem, link).replace(/^\s*[—-]\s*/, '').trim();
    entries.push({ kind, title, description, href: link.url, sourceOrder });
  }

  return entries;
};

export const parseTodoReadme = (source, sourcePath = README_PATH) => {
  const tree = parseMarkdown(source);
  const activeItems = parseRegistrySection({
    nodes: findSectionNodes(tree, '진행 중', sourcePath),
    kind: 'item',
    sourcePath,
  });
  const snapshots = parseRegistrySection({
    nodes: findSectionNodes(tree, '점검 스냅샷', sourcePath),
    kind: 'snapshot',
    sourcePath,
  });

  return { activeItems, snapshots };
};

const normalizeInternalTarget = ({ sourcePath, pathname }) => {
  if (!pathname || pathname.includes('\\') || pathname.includes('\0')) {
    throw new Error(`${sourcePath}: invalid TODO link target: ${pathname}`);
  }

  const target = path.posix.normalize(path.posix.join(path.posix.dirname(sourcePath), pathname));

  if (path.posix.isAbsolute(pathname) || path.posix.isAbsolute(target)) {
    throw new Error(`${sourcePath}: invalid TODO link target: ${pathname}`);
  }

  return target.replace(/^\.\//, '');
};

const isOutsideDirectory = (relativePath) => relativePath === '..'
  || relativePath.startsWith(`..${path.sep}`)
  || path.isAbsolute(relativePath);

const resolveRepositoryLink = ({ sourcePath, pathname, target }, {
  todoDirectory,
  repositoryDirectory,
  repositorySourceUrl,
}) => {
  const repositoryRoot = realpathSync(repositoryDirectory);
  const targetPath = path.resolve(realpathSync(todoDirectory), target);
  const repositoryPath = path.relative(repositoryRoot, targetPath);

  if (isOutsideDirectory(repositoryPath)) {
    throw new Error(`${sourcePath}: TODO link escapes repository: ${pathname}`);
  }

  let realTarget;

  try {
    realTarget = realpathSync(targetPath);
  } catch (error) {
    if (error.code === 'ENOENT' || error.code === 'ENOTDIR') {
      throw new Error(`${sourcePath}: repository link target does not exist: ${pathname}`);
    }

    throw error;
  }

  if (isOutsideDirectory(path.relative(repositoryRoot, realTarget))) {
    throw new Error(`${sourcePath}: TODO link escapes repository: ${pathname}`);
  }

  if (!statSync(realTarget).isFile()) {
    throw new Error(`${sourcePath}: repository link target is not a file: ${pathname}`);
  }

  const encodedPath = asPosixPath(repositoryPath).split('/').map(encodeURIComponent).join('/');
  return new URL(encodedPath, `${repositorySourceUrl.replace(/\/$/, '')}/`).href;
};

export const createTodoLinkResolver = (routesBySource, repository = {}) => ({ sourcePath, pathname }) => {
  const target = normalizeInternalTarget({ sourcePath, pathname });

  if (target === 'items' || target === 'items/') {
    return '/todo';
  }

  const route = routesBySource.get(target);

  if (route) {
    return route;
  }

  if (repository.todoDirectory && repository.repositoryDirectory && repository.repositorySourceUrl) {
    return resolveRepositoryLink({ sourcePath, pathname, target }, repository);
  }

  throw new Error(`${sourcePath}: TODO link target does not exist: ${pathname}`);
};

const resolveRegistry = ({ entries, kind, resolveLink }) => {
  const seenRoutes = new Set();

  return entries.map((entry) => {
    const route = resolveLink({
      sourcePath: README_PATH,
      pathname: entry.href,
      url: entry.href,
    });

    if (seenRoutes.has(route)) {
      throw new Error(`${README_PATH}: duplicate ${kind} route: ${route}`);
    }

    seenRoutes.add(route);
    return { ...entry, route };
  });
};

export const sortTodoItems = (items) => [...items].sort((left, right) => {
  const priorityDifference = (
    priorityDetails(left.priority).rank ?? PRIORITY_UNKNOWN_ORDER
  ) - (
    priorityDetails(right.priority).rank ?? PRIORITY_UNKNOWN_ORDER
  );

  if (priorityDifference !== 0) {
    return priorityDifference;
  }

  if (left.sourceOrder !== right.sourceOrder) {
    return left.sourceOrder - right.sourceOrder;
  }

  return left.route.localeCompare(right.route);
});

const registryByRoute = (entries) => new Map(entries.map((entry) => [entry.route, entry]));

const metadataText = (metadata, label) => metadata.find((entry) => entry.label === label)?.text ?? '';

export const loadTodoContent = async ({
  todoDirectory,
  repositoryDirectory,
  repositorySourceUrl,
  onWarning = (message) => console.warn(message),
} = {}) => {
  if (!todoDirectory) {
    throw new Error('loadTodoContent requires todoDirectory');
  }

  const markdownFiles = await collectMarkdownFiles(todoDirectory);

  if (!markdownFiles.includes(README_PATH)) {
    throw new Error(`${todoDirectory}: missing ${README_PATH}`);
  }

  const sourcePaths = markdownFiles.filter(
    (sourcePath) => sourcePath !== README_PATH && todoRouteForSource(sourcePath),
  );
  const routesBySource = new Map([
    [README_PATH, '/todo'],
    ...sourcePaths.map((sourcePath) => [
    sourcePath,
    todoRouteForSource(sourcePath),
    ]),
  ]);
  const routes = [...routesBySource.values()];

  if (new Set(routes).size !== routes.length) {
    throw new Error('docs/todo contains duplicate canonical routes');
  }

  const resolveRegistryLink = createTodoLinkResolver(routesBySource);
  const resolveLink = createTodoLinkResolver(routesBySource, {
    todoDirectory,
    repositoryDirectory,
    repositorySourceUrl,
  });
  const readmeSource = await readFile(path.join(todoDirectory, README_PATH), 'utf8');
  const parsedRegistry = parseTodoReadme(readmeSource);
  const activeRegistry = resolveRegistry({
    entries: parsedRegistry.activeItems,
    kind: 'item',
    resolveLink: resolveRegistryLink,
  });
  const snapshotRegistry = resolveRegistry({
    entries: parsedRegistry.snapshots,
    kind: 'snapshot',
    resolveLink: resolveRegistryLink,
  });
  const activeByRoute = registryByRoute(activeRegistry);
  const snapshotByRoute = registryByRoute(snapshotRegistry);
  const sources = await Promise.all(sourcePaths.map(async (sourcePath) => ({
    sourcePath,
    source: await readFile(path.join(todoDirectory, sourcePath), 'utf8'),
  })));
  const documents = await Promise.all(sources.map(async ({ sourcePath, source }) => {
    const route = routesBySource.get(sourcePath);
    const kind = documentKind(sourcePath);
    const rendered = await renderTodoMarkdown({ source, sourcePath, resolveLink });
    const activeEntry = activeByRoute.get(route);
    const snapshotEntry = snapshotByRoute.get(route);

    if (activeEntry && kind !== 'item') {
      throw new Error(`${README_PATH}: active item does not target an item document: ${activeEntry.href}`);
    }

    if (snapshotEntry && kind !== 'snapshot') {
      throw new Error(`${README_PATH}: snapshot does not target a snapshot document: ${snapshotEntry.href}`);
    }

    if (activeEntry && activeEntry.title !== rendered.title) {
      throw new Error(
        `${README_PATH}: link title "${activeEntry.title}" does not match H1 "${rendered.title}" (${sourcePath})`,
      );
    }

    if (kind === 'item' && rendered.priority === 'unknown') {
      onWarning(`${sourcePath}: unknown or missing priority; rendering as 미지정`);
    }

    const category = activeEntry?.category ?? (kind === 'item' ? deriveCategory(sourcePath) : '');
    const service = metadataText(rendered.metadata, '서비스');
    const snapshotDate = kind === 'snapshot' ? route.slice('/todo/history/'.length) : '';

    return {
      id: documentId(sourcePath),
      kind,
      sourcePath,
      route,
      ...rendered,
      category,
      service,
      searchText: kind === 'item'
        ? normalizeSearchText([rendered.title, service, category].filter(Boolean).join(' '))
        : '',
      sourceOrder: activeEntry?.sourceOrder ?? snapshotEntry?.sourceOrder ?? Number.MAX_SAFE_INTEGER,
      active: Boolean(activeEntry),
      date: snapshotDate,
      displayDate: snapshotDate ? displayDate(snapshotDate) : '',
      description: snapshotEntry?.description ?? '',
    };
  }));
  const documentsByRoute = Object.fromEntries(documents.map((document) => [document.route, document]));
  const items = documents.filter(({ kind }) => kind === 'item');
  const activeItems = sortTodoItems(items.filter(({ active }) => active));
  const snapshots = snapshotRegistry.map(({ route }) => documentsByRoute[route]);

  return {
    items,
    activeItems,
    snapshots,
    documents,
    documentsByRoute,
  };
};
