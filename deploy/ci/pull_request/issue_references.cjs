'use strict';

function referenceList(text, repository) {
  const numbers = [];
  let remaining = text.trim();
  while (remaining) {
    const match = remaining.match(/^(?:([\w.-]+\/[\w.-]+)?#([1-9]\d*)(?!\w)|(https?:\/\/[^\s<>,;]+))/i);
    if (!match) break;
    let name = match[1] || repository;
    let number = match[2];
    if (match[3]) {
      const url = match[3].match(/^https:\/\/github\.com\/([\w.-]+\/[\w.-]+)\/issues\/([1-9]\d*)(?=$|[?#).!])/i);
      name = url?.[1];
      number = url?.[2];
    }
    if (name?.toLowerCase() === repository.toLowerCase() && Number.isSafeInteger(Number(number))) {
      numbers.push(Number(number));
    }
    remaining = remaining.slice(match[0].length).trimStart();
    const separator = remaining.match(/^(?:[,;&]|and\b|및|와|과)\s*/i);
    if (separator) remaining = remaining.slice(separator[0].length);
  }
  return { numbers, remaining };
}

function referencedIssueNumbers(body, repository) {
  let fence;
  const text = (body || '')
    .replace(/<!--[\s\S]*?(?:-->|$)/g, '')
    .replace(/<(blockquote|pre|code)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, '')
    .split(/\r?\n/).filter(line => {
      const marker = line.match(/^ {0,3}(`{3,}|~{3,})/);
      if (fence) {
        if (marker && marker[1][0] === fence[0] && marker[1].length >= fence.length &&
            line.slice(marker[0].length).trim() === '') fence = null;
        return false;
      }
      if (marker) {
        fence = marker[1];
        return false;
      }
      return !/^(?:\s*>| {4}|\t)/.test(line);
    }).join('\n').replace(/(`+)[\s\S]*?\1(?!`)/g, '')
    // Link labels can refer to a different repository or to a workflow run.
    .replace(/!?\[[^\]\n]*\]\((https?:\/\/[^\s)]+)(?:\s+"[^"\n]*")?\)/gi, '$1')
    .replace(/<a\b[^>]*href=["'](https?:\/\/[^"']+)["'][^>]*>[\s\S]*?<\/a\s*>/gi, '$1');

  const numbers = new Set();
  let issueSection = false;
  for (const rawLine of text.split('\n')) {
    const line = rawLine.replace(/\*\*/g, '');
    const heading = line.match(/^\s*#{1,6}\s+(.+?)\s*$/);
    if (heading) {
      issueSection = /^(?:(?:관련|해결한|해결할)\s*이슈|(?:related|resolved|fixed)\s+issues?)\s*[:：]?$/i.test(heading[1]);
    }
    // Only declarations identify work this PR completes; prose can mention follow-up issues.
    for (const declaration of line.matchAll(/(?:\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\b|관련\s*이슈|\brelated\s+issues?\b)\s*[:：]?\s*/gi)) {
      for (const number of referenceList(line.slice(declaration.index + declaration[0].length), repository).numbers) {
        numbers.add(number);
      }
    }
    const list = referenceList(line.replace(/^\s*[-*+]\s+(?:\[[ xX]\]\s*)?/, ''), repository);
    if (issueSection || !list.remaining.replace(/[.!]$/, '').trim()) {
      for (const number of list.numbers) numbers.add(number);
    }
  }
  return [...numbers];
}

module.exports = { referencedIssueNumbers };
