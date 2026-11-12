// 자바스크립트 작업대 실행기. JsBenchDialog 가 설치본 Node 로 돌린다(JsBench.command).
// 사용: node harness.js <mode> <작업 폴더> <출력 한도(글자)> [typescript lib 폴더]
//   mode: run-js · run-ts(코드를 돌림), tsv · camel · ts · props · pretty(입력 데이터 바꾸기)
//   작업 폴더: code.txt(코드) · data.txt(입력 데이터)를 UTF-8 로 둔다.
// 출력: 한 줄에 사건 하나, MARK 뒤에 JSON {kind, text?, columns?, rows?}. kind 는 log · warn · error · table · value · out · truncated.
// 내보낸 글이 출력 한도를 넘으면 truncated 를 내고 바로 끝낸다(끝없는 반복 안의 console.log 가 메모리를 채우지 않게).
// 코드 안의 process.stdout.write 처럼 MARK 없이 나온 줄은 작업대가 그대로 보인다.
'use strict';
const fs = require('fs');
const path = require('path');
const util = require('util');
const vm = require('vm');

const MARK = '\u001eJSB ';
const CODE_FILE = '작업대.js';
// 표 하나에 보일 최대 줄 수. 넘는 줄은 빼고 마지막에 「… n줄 더」 를 단다.
const TABLE_LIMIT = 1000;
const INSPECT = { depth: 6, colors: false, breakLength: 100, maxArrayLength: 200, maxStringLength: 10000 };

const TIMERS = ['Timeout', 'Immediate'];

const [, , mode, dir, limitArg, tsLib] = process.argv;
const limit = Number(limitArg);
let written = 0;
// 표준 출력이 파이프면 process.stdout.write 는 비동기라, 끝없는 반복 안에서는 한 줄도 나가지 않고 process.exit 때 버려진다.
// 그래서 사건은 fd 1 에 바로(동기로) 쓴다. 파이프가 차서 EAGAIN 이면 작업대가 읽어 갈 때까지 다시 쓴다.
function write(s) {
  const b = Buffer.from(s, 'utf8');
  let off = 0;
  while (off < b.length) {
    try {
      off += fs.writeSync(1, b, off);
    } catch (e) {
      if (e.code !== 'EAGAIN') throw e;
    }
  }
}

function emit(kind, payload) {
  const line = MARK + JSON.stringify(Object.assign({ kind }, payload)) + '\n';
  written += line.length;
  if (written > limit) {
    write(MARK + JSON.stringify({ kind: 'truncated' }) + '\n');
    process.exit(3);
  }
  write(line);
}

function text(args) {
  return args.map(a => (typeof a === 'string' ? a : util.inspect(a, INSPECT))).join(' ');
}

function cell(v) {
  if (v === undefined) return '';
  if (v === null) return 'null';
  if (typeof v === 'string') return v;
  if (v instanceof Date) return isNaN(v) ? 'Invalid Date' : v.toISOString();
  if (typeof v === 'object') return util.inspect(v, { depth: 2, breakLength: Infinity, colors: false });
  return String(v);
}

// 배열 · 객체를 표(칸 이름 · 줄)로 바꾼다. 표로 볼 수 없는 값(숫자 · 글 하나)은 null.
function toTable(value, only) {
  if (value === null || typeof value !== 'object') return null;
  if (value instanceof Map) value = Object.fromEntries(value);
  if (value instanceof Set) value = Array.from(value);
  const isArray = Array.isArray(value);
  const keys = isArray ? value.map((_, i) => i) : Object.keys(value);
  const columns = [];
  let primitive = false;
  for (const k of keys) {
    const row = value[k];
    if (row !== null && typeof row === 'object' && !(row instanceof Date)) {
      for (const c of Object.keys(row)) if (!columns.includes(c)) columns.push(c);
    } else {
      primitive = true;
    }
  }
  const shown = Array.isArray(only) && only.length ? columns.filter(c => only.includes(c)) : columns;
  const head = [isArray ? '#' : '키'].concat(shown, primitive ? ['값'] : []);
  const rows = [];
  for (const k of keys.slice(0, TABLE_LIMIT)) {
    const row = value[k];
    const isObject = row !== null && typeof row === 'object' && !(row instanceof Date);
    const line = [String(k)];
    for (const c of shown) line.push(isObject ? cell(row[c]) : '');
    if (primitive) line.push(isObject ? '' : cell(row));
    rows.push(line);
  }
  return { columns: head, rows, more: Math.max(0, keys.length - TABLE_LIMIT) };
}

function table(value, only) {
  const t = toTable(value, only);
  if (t) emit('table', t);
  else emit('log', { text: text([value]) });
}

// 배열이거나 그냥 객체(클래스 인스턴스가 아닌 {})면 true.
function isPlain(v) {
  if (Array.isArray(v)) return true;
  if (v === null || typeof v !== 'object') return false;
  const proto = Object.getPrototypeOf(v);
  return proto === Object.prototype || proto === null;
}

// 입력 데이터: 비었으면 null, JSON 이면 그 값, 엑셀에서 복사한 표(탭 · 첫 줄 칸 이름)면 객체 배열, 그 밖에는 글 그대로.
function readData(raw) {
  const t = raw.replace(/^﻿/, '');
  if (!t.trim()) return null;
  try {
    return JSON.parse(t);
  } catch (e) {
    if (t.includes('\t')) return parseTsv(t);
    return t;
  }
}

// 숫자로 읽어도 뜻이 같은 글만 숫자로 바꾼다. 엑셀 천 단위 쉼표(1,234.5)는 떼고 읽는다. 앞자리 0(코드 · 우편번호) · 공백이 든 값은 글로 둔다.
function typed(v) {
  if (/^-?(0|[1-9]\d{0,14})(\.\d+)?$/.test(v)) return Number(v);
  if (/^-?[1-9]\d{0,2}(,\d{3})+(\.\d+)?$/.test(v)) return Number(v.replace(/,/g, ''));
  return v;
}

function parseTsv(t) {
  const lines = t.replace(/\r\n?/g, '\n').split('\n').filter(l => l.trim() !== '');
  if (!lines.length) return [];
  const head = lines[0].split('\t').map((h, i) => h.trim() || 'COL' + (i + 1));
  return lines.slice(1).map(l => {
    const cells = l.split('\t');
    const row = {};
    head.forEach((h, i) => { row[h] = typed((cells[i] ?? '').trim()); });
    return row;
  });
}

function camel(key) {
  if (!/[_\s-]/.test(key) && key !== key.toUpperCase()) return key;
  const parts = key.toLowerCase().split(/[_\s-]+/).filter(Boolean);
  return parts.map((p, i) => (i ? p[0].toUpperCase() + p.slice(1) : p)).join('');
}

function camelKeys(v) {
  if (Array.isArray(v)) return v.map(camelKeys);
  if (v !== null && typeof v === 'object') {
    const o = {};
    for (const [k, x] of Object.entries(v)) o[camel(k)] = camelKeys(x);
    return o;
  }
  return v;
}

function pascal(name) {
  const c = camel(name);
  return c ? c[0].toUpperCase() + c.slice(1) : 'Item';
}

// JSON 값에서 TypeScript 형을 만든다. 배열 안 객체들은 칸을 합치고, 일부에만 있는 칸은 ?(선택)로 둔다.
function tsTypes(value, rootName) {
  const decls = [];
  const usedNames = new Set();
  function uniqueName(base) {
    let n = base, i = 2;
    while (usedNames.has(n)) n = base + i++;
    usedNames.add(n);
    return n;
  }
  function typeOf(v, name) {
    if (v === null) return 'null';
    if (Array.isArray(v)) {
      if (!v.length) return 'unknown[]';
      const objects = v.filter(x => x !== null && typeof x === 'object' && !Array.isArray(x));
      const others = new Set(v.filter(x => !(x !== null && typeof x === 'object' && !Array.isArray(x))).map(x => typeOf(x, name)));
      if (objects.length) others.add(objectType(objects, name));
      const parts = Array.from(others);
      return parts.length === 1 ? parts[0] + '[]' : '(' + parts.join(' | ') + ')[]';
    }
    if (typeof v === 'object') return objectType([v], name);
    return typeof v === 'bigint' ? 'number' : typeof v;
  }
  function objectType(list, name) {
    const typeName = uniqueName(pascal(name));
    const keys = [];
    for (const o of list) for (const k of Object.keys(o)) if (!keys.includes(k)) keys.push(k);
    const lines = keys.map(k => {
      const present = list.filter(o => k in o);
      const types = Array.from(new Set(present.map(o => typeOf(o[k], k.endsWith('s') && Array.isArray(o[k]) ? k.slice(0, -1) : k))));
      const optional = present.length < list.length ? '?' : '';
      const safe = /^[A-Za-z_$][\w$]*$/.test(k) ? k : JSON.stringify(k);
      return '  ' + safe + optional + ': ' + types.join(' | ') + ';';
    });
    decls.push('export interface ' + typeName + ' {\n' + lines.join('\n') + '\n}');
    return typeName;
  }
  const top = typeOf(value, rootName);
  if (!decls.length) return 'export type ' + pascal(rootName) + ' = ' + top + ';';
  return decls.reverse().join('\n\n');
}

const VUE_TYPES = { string: 'String', number: 'Number', boolean: 'Boolean' };

function emptyOf(v) {
  if (Array.isArray(v)) return '() => []';
  if (v !== null && typeof v === 'object') return '() => ({})';
  if (typeof v === 'number') return '0';
  if (typeof v === 'boolean') return 'false';
  return "''";
}

// 객체(또는 배열의 첫 객체) 칸마다 Vue 3 defineProps 한 줄.
function vueProps(value) {
  const sample = Array.isArray(value) ? value.find(x => x !== null && typeof x === 'object') : value;
  if (sample === null || typeof sample !== 'object') throw new Error('객체나 객체 배열(JSON)을 넣어 주세요.');
  const lines = Object.entries(sample).map(([k, v]) => {
    const type = Array.isArray(v) ? 'Array' : v !== null && typeof v === 'object' ? 'Object' : VUE_TYPES[typeof v] || 'String';
    const safe = /^[A-Za-z_$][\w$]*$/.test(k) ? k : JSON.stringify(k);
    return '  ' + safe + ': { type: ' + type + ', default: ' + emptyOf(v) + ' },';
  });
  return 'const props = defineProps({\n' + lines.join('\n') + '\n});';
}

function needJson(value) {
  if (value === null || typeof value === 'string') throw new Error('입력 데이터가 JSON 이 아닙니다. JSON 이나 엑셀에서 복사한 표를 넣어 주세요.');
  return value;
}

function convert(kind, raw) {
  const value = readData(raw);
  switch (kind) {
    case 'tsv': return JSON.stringify(needJson(value), null, 2);
    case 'camel': return JSON.stringify(camelKeys(needJson(value)), null, 2);
    case 'pretty': return JSON.stringify(needJson(value), null, 2);
    case 'ts': return tsTypes(needJson(value), Array.isArray(value) ? 'Row' : 'Data');
    case 'props': return vueProps(needJson(value));
    default: throw new Error('모르는 바꾸기: ' + kind);
  }
}

// 오류 글: 첫 줄은 종류와 내용, 그 아래는 사용자 코드 자리(작업대.js:줄:칸)만 남긴다.
function errorText(e) {
  if (!(e instanceof Error)) return 'throw: ' + text([e]);
  const lines = [e.name + ': ' + e.message];
  const stack = String(e.stack || '').split('\n');
  // 문법 오류는 자리가 at 줄이 아니라 스택 맨 앞(작업대.js:줄)에 온다.
  if (stack[0].startsWith(CODE_FILE + ':')) lines.push('    at ' + stack[0]);
  for (const l of stack) if (/^\s+at /.test(l) && l.includes(CODE_FILE)) lines.push(l);
  return lines.join('\n');
}

let failed = false;
function fail(e) {
  failed = true;
  emit('error', { text: errorText(e) });
}

function transpile(code) {
  if (!tsLib) throw new Error('TypeScript 를 찾지 못했습니다.');
  const ts = require(path.join(tsLib, 'typescript.js'));
  const out = ts.transpileModule(code, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, removeComments: false },
    reportDiagnostics: false,
  });
  return out.outputText.replace(/^export \{\};\s*$/m, '');
}

// 코드를 이 프로세스 전역에서 돌린다. 마지막 식의 값을 돌려받으려 먼저 그대로 컴파일하고,
// 최상위 await 때문에 문법 오류가 나면 async 함수로 감싸 다시 한다(이때는 return 한 값이 결과). 감쌀 때 줄 번호가 밀리지 않게 같은 줄에 붙인다.
async function runCode(code) {
  let script;
  try {
    script = new vm.Script(code, { filename: CODE_FILE });
  } catch (e) {
    if (!(e instanceof SyntaxError) || !/await|return/.test(e.message)) throw e;
    script = new vm.Script('(async () => {' + code + '\n})()', { filename: CODE_FILE });
  }
  let value = script.runInThisContext();
  if (value && typeof value.then === 'function') value = await value;
  return value;
}

function main() {
  const code = mode.startsWith('run-') ? fs.readFileSync(path.join(dir, 'code.txt'), 'utf8') : '';
  const raw = fs.readFileSync(path.join(dir, 'data.txt'), 'utf8');
  if (!mode.startsWith('run-')) {
    emit('out', { text: convert(mode, raw) });
    return;
  }
  console.log = console.info = console.debug = (...a) => emit('log', { text: text(a) });
  console.warn = (...a) => emit('warn', { text: text(a) });
  console.error = (...a) => emit('error', { text: text(a) });
  console.table = (v, only) => table(v, only);
  globalThis.data = readData(raw);
  globalThis.require = require;
  globalThis.table = table;
  process.on('uncaughtException', fail);
  process.on('unhandledRejection', fail);
  process.on('exit', () => { if (failed) process.exitCode = 1; });
  const source = mode === 'run-ts' ? transpile(code) : code;
  runCode(source).then(value => {
    // setTimeout · setInterval 이 돌려준 타이머는 결과로 보이지 않는다.
    if (value === undefined || TIMERS.includes(value?.constructor?.name)) return;
    if (isPlain(value)) table(value);
    emit('value', { text: text([value]) });
  }, fail);
}

try {
  main();
} catch (e) {
  fail(e);
}
