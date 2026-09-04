import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkRehype from 'remark-rehype';
import rehypeSlug from 'rehype-slug';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import rehypeStringify from 'rehype-stringify';
import { visit } from 'unist-util-visit';
import { toString } from 'mdast-util-to-string';

const PRIORITIES = {
  high: { label: '높음', rank: 1 },
  medium: { label: '중간', rank: 2 },
  low: { label: '낮음', rank: 3 },
  unknown: { label: '미지정', rank: 4 },
};

const ALLOWED_EXTERNAL_PROTOCOLS = new Set(['http:', 'https:', 'mailto:']);
const EXTERNAL_PROTOCOLS = new Set(['http:', 'https:']);
const PRIORITY_MARKERS = new Map([
  ['🔴', '높음'],
  ['🟠', '중간'],
  ['🟢', '낮음'],
]);
const PRIORITY_CLASSES = new Map([
  ['높음', 'high'],
  ['중간', 'medium'],
  ['낮음', 'low'],
  ['미지정', 'unknown'],
]);

const appendUnique = (values = [], additions = []) => {
  const result = [...values];

  for (const addition of additions) {
    if (!result.some((value) => String(value) === String(addition))) {
      result.push(addition);
    }
  }

  return result;
};

const sanitizeSchema = {
  ...defaultSchema,
  clobberPrefix: 'todo-',
  tagNames: appendUnique(defaultSchema.tagNames, ['input']),
  attributes: {
    ...defaultSchema.attributes,
    '*': appendUnique(defaultSchema.attributes?.['*'], ['id']),
    code: appendUnique(defaultSchema.attributes?.code, [
      ['className', /^language-[A-Za-z0-9_-]+$/],
    ]),
    input: appendUnique(defaultSchema.attributes?.input, [
      ['type', 'checkbox'],
      'checked',
      'disabled',
    ]),
    li: appendUnique(defaultSchema.attributes?.li, [
      ['className', 'task-list-item'],
    ]),
    ul: appendUnique(defaultSchema.attributes?.ul, [
      ['className', 'contains-task-list'],
    ]),
  },
  protocols: {
    ...defaultSchema.protocols,
    href: appendUnique(defaultSchema.protocols?.href, ['http', 'https', 'mailto']),
  },
};

export const parseMarkdown = (source) => unified()
  .use(remarkParse)
  .use(remarkGfm)
  .parse(source);

const safeDecodeURIComponent = (value) => {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
};

const prefixFragment = (fragment) => {
  const decoded = safeDecodeURIComponent(fragment.replace(/^#/, ''));

  if (!decoded) {
    return '';
  }

  return `#${decoded.startsWith('todo-') ? decoded : `todo-${decoded}`}`;
};

const splitUrl = (url) => {
  const hashIndex = url.indexOf('#');
  const beforeHash = hashIndex === -1 ? url : url.slice(0, hashIndex);
  const fragment = hashIndex === -1 ? '' : url.slice(hashIndex + 1);
  const queryIndex = beforeHash.indexOf('?');

  return {
    pathname: queryIndex === -1 ? beforeHash : beforeHash.slice(0, queryIndex),
    query: queryIndex === -1 ? '' : beforeHash.slice(queryIndex),
    fragment,
  };
};

const rewriteUrl = ({ url, sourcePath, resolveLink }) => {
  const trimmed = url.trim();

  if (!trimmed) {
    return trimmed;
  }

  if (trimmed.startsWith('//')) {
    throw new Error(`${sourcePath}: protocol-relative links are not supported: ${url}`);
  }

  const protocolMatch = /^([A-Za-z][A-Za-z\d+.-]*:)/.exec(trimmed);

  if (protocolMatch) {
    const protocol = protocolMatch[1].toLowerCase();

    if (!ALLOWED_EXTERNAL_PROTOCOLS.has(protocol)) {
      throw new Error(`${sourcePath}: unsafe link protocol: ${protocol}`);
    }

    return trimmed;
  }

  const { pathname, query, fragment } = splitUrl(trimmed);
  const rewrittenFragment = fragment ? prefixFragment(fragment) : '';

  if (!pathname) {
    return `${query}${rewrittenFragment}`;
  }

  if (pathname.startsWith('/')) {
    return `${pathname}${query}${rewrittenFragment}`;
  }

  if (typeof resolveLink !== 'function') {
    throw new Error(`${sourcePath}: cannot resolve relative link without a resolver: ${url}`);
  }

  const route = resolveLink({ sourcePath, pathname: safeDecodeURIComponent(pathname), url });
  const resolvedFragment = /^https?:\/\//i.test(route)
    ? (fragment ? `#${fragment}` : '')
    : rewrittenFragment;
  return `${route}${query}${resolvedFragment}`;
};

export const rewriteMarkdownLinks = (tree, { sourcePath, resolveLink }) => {
  visit(tree, (node) => {
    if (node.type === 'image' || node.type === 'imageReference') {
      throw new Error(`${sourcePath}: images are not supported in TODO documents`);
    }

    if (node.type === 'link' || node.type === 'definition') {
      node.url = rewriteUrl({ url: node.url, sourcePath, resolveLink });
    }
  });

  return tree;
};

export const normalizePriorityMarkers = (tree) => {
  visit(tree, 'text', (node) => {
    let value = node.value;

    for (const [marker, label] of PRIORITY_MARKERS) {
      value = value.replaceAll(marker, label);
    }

    node.value = value
      .replace(/높음\s+높음/g, '높음')
      .replace(/중간\s+중간/g, '중간')
      .replace(/낮음\s+낮음/g, '낮음');
  });

  return tree;
};

const cloneNodes = (nodes) => structuredClone(nodes);

const metadataValueNodes = (paragraph, strongIndex) => {
  const nodes = cloneNodes(paragraph.children.slice(strongIndex + 1));

  for (const node of nodes) {
    if (node.type !== 'text') {
      if (toString(node).trim()) {
        break;
      }
      continue;
    }

    node.value = node.value.replace(/^\s*[:：]\s*/, '');
    break;
  }

  return nodes;
};

const parseMetadataItem = (listItem) => {
  const paragraph = listItem.children.find((node) => node.type === 'paragraph');

  if (!paragraph) {
    return null;
  }

  const strongIndex = paragraph.children.findIndex((node) => node.type === 'strong');

  if (strongIndex === -1) {
    return null;
  }

  const leadingText = toString({
    type: 'root',
    children: paragraph.children.slice(0, strongIndex),
  }).trim();

  if (leadingText) {
    return null;
  }

  const label = toString(paragraph.children[strongIndex]).trim();
  const valueNodes = metadataValueNodes(paragraph, strongIndex);

  if (!label) {
    return null;
  }

  return {
    label,
    text: toString({ type: 'root', children: valueNodes }).trim(),
    valueNodes,
  };
};

const extractDocumentParts = (tree, sourcePath) => {
  const h1Index = tree.children.findIndex(
    (node) => node.type === 'heading' && node.depth === 1,
  );

  if (h1Index === -1) {
    throw new Error(`${sourcePath}: TODO document must contain an H1 title`);
  }

  const title = toString(tree.children[h1Index]).trim();

  if (!title) {
    throw new Error(`${sourcePath}: TODO document H1 title must not be empty`);
  }

  const firstH2Index = tree.children.findIndex(
    (node, index) => index > h1Index && node.type === 'heading' && node.depth === 2,
  );
  const metadataLimit = firstH2Index === -1 ? tree.children.length : firstH2Index;
  let metadataIndex = -1;
  let parsedMetadata = [];

  for (let index = h1Index + 1; index < metadataLimit; index += 1) {
    const node = tree.children[index];

    if (node.type !== 'list' || node.ordered) {
      continue;
    }

    const entries = node.children.map(parseMetadataItem);

    if (entries.length > 0 && entries.every(Boolean)) {
      metadataIndex = index;
      parsedMetadata = entries;
      break;
    }
  }

  const bodyNodes = tree.children.filter(
    (_node, index) => index !== h1Index && index !== metadataIndex,
  );

  return { title, parsedMetadata, bodyNodes };
};

const hastText = (node) => {
  if (node.type === 'text') {
    return node.value;
  }

  if (!Array.isArray(node.children)) {
    return '';
  }

  return node.children.map(hastText).join('');
};

const isExternalHref = (href) => {
  const protocolMatch = /^([A-Za-z][A-Za-z\d+.-]*:)/.exec(href);
  return protocolMatch && EXTERNAL_PROTOCOLS.has(protocolMatch[1].toLowerCase());
};

const postSanitizeTransform = (toc) => () => (tree) => {
  const walk = (parent) => {
    if (!Array.isArray(parent.children)) {
      return;
    }

    const children = [];

    for (const child of parent.children) {
      if (child.type === 'element') {
        walk(child);

        if (child.tagName === 'a') {
          const href = String(child.properties?.href ?? '');

          if (href.startsWith('#')) {
            child.properties.href = prefixFragment(href);
          } else if (isExternalHref(href)) {
            child.properties.target = '_blank';
            child.properties.rel = ['noopener', 'noreferrer'];
          }
        }

        if ((child.tagName === 'h2' || child.tagName === 'h3') && child.properties?.id) {
          toc.push({
            id: String(child.properties.id),
            depth: Number(child.tagName.slice(1)),
            text: hastText(child).trim(),
          });
        }

        if (child.tagName === 'td') {
          const priority = PRIORITY_CLASSES.get(hastText(child).trim());

          if (priority) {
            child.children.unshift(
              {
                type: 'element',
                tagName: 'span',
                properties: {
                  className: [
                    'todo-priority-marker',
                    `todo-priority-marker--${priority}`,
                  ],
                  ariaHidden: 'true',
                },
                children: [],
              },
              { type: 'text', value: ' ' },
            );
          }
        }

        if (child.tagName === 'table') {
          children.push({
            type: 'element',
            tagName: 'div',
            properties: { className: ['todo-document__table-scroll'] },
            children: [child],
          });
          continue;
        }
      }

      children.push(child);
    }

    parent.children = children;
  };

  walk(tree);
};

const renderMdast = async (root) => {
  const toc = [];
  const processor = unified()
    .use(remarkRehype)
    .use(rehypeSlug)
    .use(rehypeSanitize, sanitizeSchema)
    .use(postSanitizeTransform(toc))
    .use(rehypeStringify);
  const hast = await processor.run(root);

  return {
    html: processor.stringify(hast),
    toc,
  };
};

const renderInline = async (nodes) => {
  const { html } = await renderMdast({
    type: 'root',
    children: [{ type: 'paragraph', children: cloneNodes(nodes) }],
  });

  return html.startsWith('<p>') && html.endsWith('</p>')
    ? html.slice(3, -4)
    : html;
};

export const parsePriority = (value) => {
  const text = String(value ?? '');

  if (text.includes('높음')) {
    return 'high';
  }

  if (text.includes('중간')) {
    return 'medium';
  }

  if (text.includes('낮음')) {
    return 'low';
  }

  return 'unknown';
};

export const priorityDetails = (priority) => PRIORITIES[priority] ?? PRIORITIES.unknown;

export const renderTodoMarkdown = async ({
  source,
  sourcePath,
  resolveLink,
}) => {
  const tree = parseMarkdown(source);
  normalizePriorityMarkers(tree);
  rewriteMarkdownLinks(tree, { sourcePath, resolveLink });

  const { title, parsedMetadata, bodyNodes } = extractDocumentParts(tree, sourcePath);
  const metadata = await Promise.all(parsedMetadata.map(async ({ label, text, valueNodes }) => ({
    label,
    text,
    html: await renderInline(valueNodes),
  })));
  const priorityMetadata = metadata.find(({ label }) => label === '우선순위');
  const priority = parsePriority(priorityMetadata?.text);
  const { html: bodyHtml, toc } = await renderMdast({
    type: 'root',
    children: cloneNodes(bodyNodes),
  });

  return {
    title,
    metadata,
    priority,
    priorityLabel: priorityDetails(priority).label,
    bodyHtml,
    toc,
  };
};
