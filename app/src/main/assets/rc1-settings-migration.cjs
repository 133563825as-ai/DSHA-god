'use strict';
// 仅由锁定 settings 模块调用；回执在宿主私有绑定中，归档内同名 JSON 不具权威。
const fs = require('node:fs/promises');
const { constants } = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { isDeepStrictEqual } = require('node:util');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function read(file) { return JSON.parse(await fs.readFile(file, 'utf8')); }
async function atomic(file, value) {
  const temp = file + '.' + crypto.randomUUID() + '.tmp';
  await fs.writeFile(temp, JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  await fs.rename(temp, file);
}
const clone = value => JSON.parse(JSON.stringify(value));
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
  && [Object.prototype, null].includes(Object.getPrototypeOf(value));
const own = (object, key, value) => Object.defineProperty(object, key, { value, enumerable: true, configurable: true, writable: true });
function merge(under, over) {
  if (!record(under) || !record(over)) return clone(over);
  const next = clone(under);
  for (const [key, value] of Object.entries(over)) own(next, key, Object.hasOwn(next, key) ? merge(next[key], value) : clone(value));
  return next;
}

// rc.1 返回 {uid,refs}，不是嵌套 Schema；只解释数据，不重建可执行 Schema。
// 仅支持静态对象/数组/标量/常量枚举。未知/秘密/动态节点整节拒绝，不投影后冒充完整。
function staticSchema(graph) {
  if (!record(graph) || !record(graph.refs) || Object.keys(graph).some(key => !['uid', 'refs'].includes(key))) throw Error('SCHEMA_UNSUPPORTED');
  const visiting = new Set(), reached = new Set();
  function node(uid) {
    if (!Number.isSafeInteger(uid) || !Object.hasOwn(graph.refs, uid) || visiting.has(uid)) throw Error('SCHEMA_UNSUPPORTED');
    const raw = graph.refs[uid];
    if (!record(raw) || !record(raw.meta) || raw.meta.role === 'secret') throw Error('SCHEMA_UNSUPPORTED');
    const fields = { object: ['dict'], array: ['inner'], string: [], number: [], boolean: [], const: ['value'], union: ['list'] };
    if (!Object.hasOwn(fields, raw.type) || Object.keys(raw).some(key => !['type', 'meta', ...fields[raw.type]].includes(key))) throw Error('SCHEMA_UNSUPPORTED');
    const metaKeys = ['default', 'required', 'min', 'max', 'step', 'role', 'extra', 'description', 'comment', 'link', 'disabled', 'collapse', 'hidden', 'badges'];
    if (Object.keys(raw.meta).some(key => !metaKeys.includes(key))) throw Error('SCHEMA_UNSUPPORTED');
    if (Object.hasOwn(raw.meta, 'required') && typeof raw.meta.required !== 'boolean') throw Error('SCHEMA_UNSUPPORTED');
    for (const key of ['min', 'max', 'step']) if (Object.hasOwn(raw.meta, key) && !Number.isFinite(raw.meta[key])) throw Error('SCHEMA_UNSUPPORTED');
    visiting.add(uid); reached.add(String(uid));
    // 去掉UID后保留完整语义/元数据供同次读回比较，UID每describe可变。
    const out = { type: raw.type, meta: clone(raw.meta) };
    if (raw.type === 'object') {
      if (!record(raw.dict)) throw Error('SCHEMA_UNSUPPORTED');
      out.dict = {};
      for (const key of Object.keys(raw.dict).sort()) own(out.dict, key, node(raw.dict[key]));
    } else if (raw.type === 'array') out.inner = node(raw.inner);
    else if (raw.type === 'union') {
      if (!Array.isArray(raw.list) || raw.list.length === 0) throw Error('SCHEMA_UNSUPPORTED');
      out.list = raw.list.map(node);
      if (out.list.some(child => child.type !== 'const')) throw Error('SCHEMA_UNSUPPORTED');
    } else if (raw.type === 'const') {
      if (!['string', 'number', 'boolean'].includes(typeof raw.value) && raw.value !== null) throw Error('SCHEMA_UNSUPPORTED');
      out.value = raw.value;
    }
    visiting.delete(uid);
    return out;
  }
  const schema = node(graph.uid);
  if (schema.type !== 'object' || Object.keys(graph.refs).some(uid => !reached.has(uid))) throw Error('SCHEMA_UNSUPPORTED');
  return schema;
}
function candidate(value, schema) {
  if (value === null || value === undefined) {
    if (schema.meta.required) throw Error('SCHEMA_REJECTED');
    if (schema.meta.default === null || schema.meta.default === undefined) return value;
    value = clone(schema.meta.default);
  }
  const { type, meta } = schema;
  if (type === 'object') {
    if (!record(value) || Object.keys(value).some(key => !Object.hasOwn(schema.dict, key))) throw Error('SCHEMA_REJECTED');
    const out = {};
    for (const [key, child] of Object.entries(schema.dict)) {
      const next = candidate(Object.hasOwn(value, key) ? value[key] : undefined, child);
      if (next !== undefined) own(out, key, next);
    }
    return out;
  }
  if (type === 'array') {
    if (!Array.isArray(value) || Object.keys(value).length !== value.length) throw Error('SCHEMA_REJECTED');
    // 保留每个索引；不通过filter删除秘密槽位或把数组当对象子集。
    value = value.map(item => candidate(item, schema.inner));
  } else if (type === 'const') {
    if (!isDeepStrictEqual(value, schema.value)) throw Error('SCHEMA_REJECTED');
  } else if (type === 'union') {
    if (!schema.list.some(child => isDeepStrictEqual(value, child.value))) throw Error('SCHEMA_REJECTED');
  } else if (typeof value !== type || type === 'number' && !Number.isFinite(value)) throw Error('SCHEMA_REJECTED');
  const size = type === 'string' || type === 'array' ? value.length : type === 'number' ? value : undefined;
  if (size !== undefined && (size < (meta.min ?? -Infinity) || size > (meta.max ?? Infinity))) throw Error('SCHEMA_REJECTED');
  // 小数step的运行时舍入规则不在本静态子集；保守拒绝，不模拟整个验证框架。
  if (meta.step !== undefined && (type !== 'number' || !Number.isInteger(meta.step) || meta.step <= 0 || (value - (meta.min ?? 0)) % meta.step !== 0)) throw Error('SCHEMA_REJECTED');
  return value;
}
function jsonData(value, visiting = new Set()) {
  if (value === null || ['string', 'boolean'].includes(typeof value) || typeof value === 'number' && Number.isFinite(value)) return;
  if (Array.isArray(value)) {
    if (Object.keys(value).length !== value.length) throw Error('SCHEMA_REJECTED');
  } else if (!record(value)) throw Error('SCHEMA_REJECTED');
  if (visiting.has(value)) throw Error('SCHEMA_REJECTED');
  visiting.add(value);
  for (const child of Object.values(value)) jsonData(child, visiting);
  visiting.delete(value);
}
function knownPatch(value, schema) {
  if (schema.type !== 'object') return;
  if (!record(value) || Object.keys(value).some(key => !Object.hasOwn(schema.dict, key))) throw Error('SCHEMA_REJECTED');
  for (const [key, child] of Object.entries(value)) knownPatch(child, schema.dict[key]);
}
function importContext(current, prepared, sourceSha) {
  const context = current.startupContext;
  const valid = context && context.version === 3 && context.generation === current.generation
    && typeof current.startupId === 'string' && current.startupId.length > 0 && context.startupId === current.startupId
    && typeof prepared.inputDigest === 'string' && context.inputFingerprint === prepared.inputDigest
    && context.settingsSha256 === sourceSha && typeof context.proofDigest === 'string';
  if (!valid) return false;
  return context.mode === 'write-allowed' && context.proofDigest === hash(JSON.stringify({
    generation: current.generation, inputDigest: prepared.inputDigest, settingsSha256: sourceSha,
  }));
}
async function checkpointMatches(folder, prepared, sourceSha, expected) {
  let file;
  try {
    file = await fs.open(path.join(folder, 'reuse.json'), constants.O_RDONLY | constants.O_NOFOLLOW);
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > 65536) return false;
    const bytes = Buffer.alloc(65537), { bytesRead } = await file.read(bytes, 0, bytes.length, 0);
    if (bytesRead > 65536 || bytesRead !== stat.size) return false;
    const checkpoint = JSON.parse(bytes.subarray(0, bytesRead).toString('utf8'));
    if (!record(checkpoint)) return false;
    if (!Object.hasOwn(checkpoint, 'settingsSections')) return true;
    if (checkpoint.version !== 2 || checkpoint.generation !== prepared.generation
      || checkpoint.settingsSha256 !== sourceSha || checkpoint.inputDigest !== prepared.inputDigest
      || !/^[a-f0-9]{64}$/.test(prepared.inputDigest) || !isDeepStrictEqual(checkpoint.dataRoot, prepared.dataRoot)
      || !record(checkpoint.dataRoot) || !record(checkpoint.inputs) || !record(prepared.inputs)
      || !expectedMatches(checkpoint.settingsSections, expected)) return false;
    for (const inputs of [checkpoint.inputs, prepared.inputs]) {
      if (Object.entries(inputs).some(([name, sha]) => !['settings.yaml', 'settings.yaml.imported', 'restore'].includes(name)
        || typeof sha !== 'string' || !/^[a-f0-9]{64}$/.test(sha))) return false;
    }
    // 与宿主same_input一致：仅合法单份settings→imported改名可归一化。
    const both = inputs => Object.hasOwn(inputs, 'settings.yaml') && Object.hasOwn(inputs, 'settings.yaml.imported');
    const normalized = inputs => ({ settings: inputs['settings.yaml'] ?? inputs['settings.yaml.imported'], restore: inputs.restore });
    return isDeepStrictEqual(both(checkpoint.inputs) || both(prepared.inputs) ? checkpoint.inputs : normalized(checkpoint.inputs),
      both(checkpoint.inputs) || both(prepared.inputs) ? prepared.inputs : normalized(prepared.inputs));
  } catch (error) { return error.code === 'ENOENT'; }
  finally { if (file) await file.close(); }
}
function expectedMatches(declared, expected) {
  return Array.isArray(declared) && declared.every(name => typeof name === 'string' && name.length > 0)
    && new Set(declared).size === declared.length && isDeepStrictEqual([...declared].sort(), expected);
}
function sectionState(sections, expected) {
  const states = expected.map(name => Object.hasOwn(sections, name) ? sections[name]?.status : undefined);
  if (states.some(status => status === 'writing' || status === 'unknown' || status === undefined)) return 'unknown';
  return states.every(status => status === 'verified') ? 'verified' : 'pending';
}

async function migrate(settings, parse, aliases, options = {}) {
  const home = settings.ownerContext.profileContext.home;
  const state = options.stateRoot || '/run/dsha-rc1-state';
  let current;
  try { current = await read(path.join(state, 'current.json')); }
  catch (error) {
    let empty;
    try { empty = parse(await fs.readFile(path.join(home, 'settings.yaml'), 'utf8')); }
    catch (missing) { if (missing.code === 'ENOENT') return; throw missing; }
    if (empty && Object.keys(empty).length === 0) return;
    throw new Error('DSHA_MIGRATION_PROTECTION_REQUIRED');
  }
  if (current.version !== 2 || current.dshHome !== home || !/^[a-f0-9-]{36}$/.test(current.generation)) {
    if (process.env.DSHA_RUNTIME_TRIAL_NONCE) return;
    throw new Error('DSHA_MIGRATION_GENERATION_MISMATCH');
  }
  const folder = path.join(state, 'generations', current.generation);
  const prepared = await read(path.join(folder, 'prepare.json'));
  if (prepared.version !== 2 || prepared.generation !== current.generation || prepared.dshHome !== home
    || prepared.status !== 'prepared' || prepared.protectionComplete !== true) throw new Error('DSHA_MIGRATION_PROTECTION_REQUIRED');
  const sources = prepared.sources.filter(row => row.kind === 'settings');
  const source = sources.find(row => row.path === 'settings.yaml') || sources.find(row => row.path === 'settings.yaml.imported');
  if (!source) return;
  if (!/^snapshots\/[0-9]+$/.test(source.snapshot)) throw new Error('DSHA_MIGRATION_SNAPSHOT_PATH');
  const bytes = await fs.readFile(path.join(folder, source.snapshot));
  if (hash(bytes) !== source.sha256) throw new Error('DSHA_MIGRATION_SNAPSHOT_CHANGED');
  const writeAllowed = importContext(current, prepared, source.sha256);
  const activePath = path.join(home, 'settings.yaml'), importedPath = activePath + '.imported';
  let sourcePath;
  for (const file of [activePath, importedPath]) {
    try { if (hash(await fs.readFile(file)) === source.sha256) { sourcePath = file; break; } }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  if (!sourcePath) throw new Error('DSHA_MIGRATION_SOURCE_CHANGED');
  const sections = parse(bytes.toString('utf8')) ?? {};
  if (!record(sections)) throw new Error('DSHA_MIGRATION_SETTINGS_FORMAT');
  const expected = Object.keys(sections).sort(); // 独立于既有ledger keys的受保护源全集。
  const ledgerFile = path.join(folder, 'settings-result.json');
  const unknown = reason => ({ status: 'unknown', reason, generation: current.generation });
  if (expected.some(name => name.length === 0)) return unknown('SETTINGS_EXPECTED_SECTIONS');
  if (!await checkpointMatches(folder, prepared, source.sha256, expected)) return unknown('SETTINGS_EXPECTED_SECTIONS_CHECKPOINT');
  let result;
  try { result = await read(ledgerFile); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (result !== undefined && (!record(result) || result.version !== 2 || result.generation !== current.generation || result.sourceSha256 !== source.sha256)) return unknown('DSHA_MIGRATION_RESULT_IDENTITY');
  if (result !== undefined && !['verified', 'pending', 'unknown', 'writing'].includes(result.status)) return unknown('DSHA_MIGRATION_RESULT_FORMAT');
  if (result === undefined) {
    if (!writeAllowed) return unknown('DSHA_MIGRATION_LEDGER_MISSING');
    result = { version: 2, generation: current.generation, sourceSha256: source.sha256, targetVersion: '0.1.7-rc.2', status: 'pending', sections: {} };
  }
  if (Object.hasOwn(result, 'inputDigest') && result.inputDigest !== prepared.inputDigest) return unknown('DSHA_MIGRATION_RESULT_IDENTITY');
  if (!record(result.sections) || Object.values(result.sections).some(row => !record(row)
    || !['verified', 'pending', 'conflict', 'writing', 'unknown'].includes(row.status))) return unknown('DSHA_MIGRATION_RESULT_FORMAT');
  if (Object.hasOwn(result, 'expectedSections') && !expectedMatches(result.expectedSections, expected)) return unknown('SETTINGS_EXPECTED_SECTIONS');
  if (Object.keys(result.sections).some(name => !Object.hasOwn(sections, name))) return unknown('SETTINGS_UNEXPECTED_SECTION');
  if (!writeAllowed) {
    // 不改startupId/时间/节状态，不写账本，不rename，不调用设置服务。
    if (expected.some(name => !Object.hasOwn(result.sections, name))) return unknown('SETTINGS_SECTION_MISSING');
    const status = sectionState(result.sections, expected);
    if (status === 'unknown' || result.status === 'unknown' || result.status === 'writing') return unknown('SETTINGS_WRITE_OUTCOME_UNKNOWN');
    if (status === 'verified' && result.status !== 'verified') return unknown('SETTINGS_LEDGER_STATUS');
    if (expected.some(name => !['verified', 'pending', 'conflict'].includes(result.sections[name]?.status))) return unknown('SETTINGS_LEDGER_SECTION');
    return { status, ...(status === 'pending' ? { reason: 'SETTINGS_READ_ONLY_INCOMPLETE' } : {}), generation: current.generation };
  }
  if (result.status === 'unknown' || result.status === 'writing') return unknown('SETTINGS_WRITE_OUTCOME_UNKNOWN');
  result.startupId = current.startupId;
  result.expectedSections = expected;
  const targets = Object.entries(sections).map(([name]) => [name, Object.hasOwn(aliases, name) ? aliases[name] : name]);
  // 整源预检先于任何update；不能先写安全A，再发现B的secret或别名冲突。
  const plans = new Map();
  let unsafe = false;
  for (const [name, values] of Object.entries(sections)) {
    const ns = targets.find(row => row[0] === name)[1];
    let descriptor;
    try {
      if (typeof ns !== 'string' || !ns || targets.filter(row => row[1] === ns).length !== 1
        || Object.hasOwn(result.sections, name) && Object.hasOwn(result.sections[name], 'ns') && result.sections[name].ns !== ns) throw Error('SECTION_ALIAS_CONFLICT');
      const matches = settings.describe().filter(row => row.ns === ns);
      if (matches.length !== 1) throw Error('SECTION_UNAVAILABLE');
      descriptor = matches[0];
      jsonData(descriptor.schema); jsonData(descriptor.value); jsonData(values);
      const schema = staticSchema(descriptor.schema);
      knownPatch(values, schema);
      const before = candidate(descriptor.value, schema);
      plans.set(name, { descriptor, schema, before, next: candidate(merge(before, values), schema) });
    } catch (error) {
      unsafe = true;
      plans.set(name, { reason: error.message === 'SECTION_ALIAS_CONFLICT' ? error.message : descriptor ? 'SECTION_SCHEMA_REJECTED' : 'SECTION_UNAVAILABLE' });
    }
  }
  for (const [name, values] of Object.entries(sections)) {
    const ns = targets.find(row => row[0] === name)[1];
    let section = Object.hasOwn(result.sections, name) ? result.sections[name] : undefined;
    if (section?.status === 'verified' || section?.status === 'unknown') continue;
    if (section?.status === 'writing') {
      section.status = 'unknown'; section.reason = 'WRITE_OUTCOME_UNKNOWN';
      await atomic(ledgerFile, result); continue;
    }
    if (section && !['pending', 'conflict'].includes(section.status)) return unknown('SETTINGS_LEDGER_SECTION');
    if (!section) { section = { ns, status: 'pending' }; own(result.sections, name, section); }
    if (unsafe) {
      // 仅调用update前的本地拒绝可保留pending；不保存未知或秘密值。
      section.reason = plans.get(name).reason || 'SOURCE_PREFLIGHT_REJECTED';
      await atomic(ledgerFile, result); continue;
    }
    const { descriptor, schema, before, next } = plans.get(name);
    if (Object.hasOwn(section, 'before') && !isDeepStrictEqual(section.before, before)) {
      section.status = 'conflict'; section.reason = 'TARGET_CHANGED_REVIEW_REQUIRED';
      await atomic(ledgerFile, result); continue;
    }
    section.source = clone(values); section.before = clone(before); section.candidate = clone(next); section.status = 'writing';
    await atomic(ledgerFile, result);
    try {
      await settings.update(ns, values, descriptor.revision);
      const matches = settings.describe().filter(row => row.ns === ns);
      const after = matches.length === 1 ? matches[0] : undefined;
      if (!after) throw Error('SETTINGS_READBACK_MISMATCH');
      jsonData(after.schema); jsonData(after.value);
      if (!isDeepStrictEqual(staticSchema(after.schema), schema)) throw Error('SETTINGS_READBACK_MISMATCH');
      candidate(after.value, schema);
      if (!isDeepStrictEqual(after.value, next)) throw Error('SETTINGS_READBACK_MISMATCH');
      section.status = 'verified'; section.verified = clone(next); delete section.reason;
    } catch {
      // update一旦进入，任何错误文本/读回异常均不能证明未派发。
      section.status = 'unknown'; section.reason = 'WRITE_OUTCOME_UNKNOWN';
    }
    await atomic(ledgerFile, result);
  }
  result.status = sectionState(result.sections, expected);
  if (result.status === 'unknown') result.reason = 'SETTINGS_WRITE_OUTCOME_UNKNOWN'; else delete result.reason;
  result.verifiedAt = Date.now();
  await atomic(ledgerFile, result);
  try {
    if (hash(await fs.readFile(activePath)) === source.sha256) {
      try { if (hash(await fs.readFile(importedPath)) !== source.sha256) return result; }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      await fs.rename(activePath, importedPath);
    }
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  settings.ownerContext.logger.info('DSHA rc1 settings migration: %s', result.status);
  return result;
}
module.exports = { migrate };
