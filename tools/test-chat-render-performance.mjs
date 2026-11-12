#!/usr/bin/env node
import {testRuntime} from './test-runtime-fixture.mjs';
// 只加载当前锁定 DSH 的 React/DOM 与 ui-chat，不连接真实账户，不创建设备会话。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { browserFixture } from './rc1-browser-fixture.mjs';

const runtime = testRuntime('raw');
const fixture = await browserFixture(runtime);
const errors = [];
fixture.page.on('pageerror', error => errors.push(error.message));
fixture.page.on('console', message => {
  if (message.type() === 'error' && !message.location().url.endsWith('/favicon.ico'))
    errors.push(message.text());
});
const assertNoErrors = messages => {
  if (messages.length) throw new Error(messages.join('\n'));
};
try {
  // 先验证真正的浏览器异常能经 pageerror 进入失败判据；只移除这条预期负控。
  const errorControl = 'CHAT_RENDER_ERROR_CONTROL';
  const errorEvent = fixture.page.waitForEvent('pageerror', {predicate: error => error.message === errorControl});
  await fixture.page.evaluate(marker => { setTimeout(() => { throw new Error(marker); }, 0); }, errorControl);
  await errorEvent;
  assert.throws(() => assertNoErrors(errors), /CHAT_RENDER_ERROR_CONTROL/);
  errors.splice(errors.indexOf(errorControl), 1);
  assertNoErrors(errors);
  // 当前模块CSS通过require.asset取鲸鱼图；仅补真实锁定静态资源映射，不改模块源码。
  const assetName = 'plugins/@deepseek-ai/dsh-client-ui-chat/assets/running-whale%402x-06738fa29b2f.png';
  const assetBytes = fs.readFileSync(path.join(runtime,
    'node_modules/@deepseek-ai/dsh-client-ui-chat/lib/assets/running-whale@2x-06738fa29b2f.png'));
  await fixture.page.evaluate(({name, value}) => {
    const original = window.__ModuleLoader__.load;
    window.__ModuleLoader__.load = ({id, factory}) => original({id, factory: require => {
      require.asset = requested => {
        if (requested !== name) throw new Error(`Unexpected fixture asset: ${requested}`);
        return value;
      };
      return factory(require);
    }});
  }, {name: assetName, value: `data:image/png;base64,${assetBytes.toString('base64')}`});
  await fixture.load('dsh-client-ui-chat', [], `
    module.exports.audit = {ChatFlow, UserMessageNodeView, AssistantNodeView, UserContent,
      legacyComponentType: typeof ChatNodeList};`);
  const result = await fixture.page.evaluate(async () => {
    const React = auditModules.react.default || auditModules.react;
    const ReactDOM = auditModules['react-dom'].default || auditModules['react-dom'];
    const primitives = auditModules['@deepseek-ai/dsh-client-ui-primitives'];
    const {ChatFlow, UserMessageNodeView, AssistantNodeView, UserContent, legacyComponentType} =
      auditExports['@deepseek-ai/dsh-client-ui-chat'].audit;
    if (legacyComponentType !== 'undefined') throw new Error('Legacy component absence control changed');
    if (!ChatFlow || !UserMessageNodeView || !AssistantNodeView || !UserContent)
      throw new Error('Current Chat renderers missing');
    const host = document.createElement('div');
    host.style.cssText = 'height:760px;overflow:auto';
    document.body.append(host);
    const nodes = new Map(), entries = [];
    const now = Date.now();
    for (let i = 0; i < 100; i++) {
      const key = `node-${i}`, kind = i % 5 === 0 ? 'user' : 'assistant-step';
      const streaming = i === 99;
      const text = `${kind} message-${i}:\n\n\`\`\`js\nconst message${i} = ${i};\n${streaming ? '' : '```\n'}\n${'streamed content '.repeat(8)}`;
      const turn = {turn: i + 1, status: streaming ? 'open' : 'closed', data: new Map()};
      entries.push({kind: 'node', key});
      nodes.set(key, {key, id: key, target: 'chat', kind, anchorSeq: i + 1, visibility: 'visible',
        data: kind === 'user'
          ? {kind: 'user', messageId: i, seq: i + 1, time: now,
            source: {kind: 'user'}, content: [{type: 'text', text}]}
          : {status: streaming ? 'running' : 'settled', turn: i + 1, step: 1,
            time: now, blocks: [{kind: 'text', text}]},
        location: {kind: 'turn', turn}});
    }
    const presentation = {foldCompletedTurns: false, foldedReplies: 'all'};
    const usePresentation = selector => selector(presentation);
    const useGroupAction = () => React.useRef(null);
    const useChatNode = (key, selector = node => node) => selector(nodes.get(key));
    const snapshot = {nodes, navigation: {items: () => [{anchorKey: 'node-99'}]}};
    const t = key => key;
    // 只替代框架的注入/路由层；行、用户气泡、助手 Markdown/代码块均由包内真实组件创建。
    const renderFactorySlot = (name, owner) => {
      if (name === 'conversation.chat.user.content')
        return React.createElement(UserContent, {...owner, renderSlot, renderFactorySlot, t});
      if (name === 'conversation.user.text')
        return React.createElement(primitives.UserTextRun, {text: owner.text});
      throw new Error(`Unexpected factory slot: ${name}`);
    };
    const renderSlot = (name, owner, options) => {
      if (name !== 'conversation.chat.node' || options.entryKey !== owner.node.kind)
        throw new Error(`Unexpected node slot: ${name}`);
      if (owner.node.kind === 'user')
        return React.createElement(UserMessageNodeView, {...owner, renderFactorySlot,
          renderSlotChain: () => null, t});
      if (owner.node.kind === 'assistant-step')
        return React.createElement(AssistantNodeView, {...owner, usePresentation, useGroupAction,
          useTurnData: key => options.hookContext.turnData?.get(key),
          useDisclosure: () => { throw new Error('Unexpected reasoning disclosure'); },
          useContextMenu: () => ({getTriggerProps: () => ({})}), resolveFilePath: path => path,
          fileMentions: () => undefined, renderSlot, t, sessionId: 'render-fixture'});
      throw new Error(`Unexpected node kind: ${owner.node.kind}`);
    };
    const props = {
      entries, pendingInputs: [], lastInputTurn: 100, deferCollapse: false,
      useChat: selector => selector(snapshot), useSession: selector => selector({running: true}),
      useConnectionState: selector => selector('connected'), useChatNode,
      useChatNodeBottom: key => key === 'node-99', useChatNodeProcess: () => undefined,
      useChatGroup: () => undefined, useGroupAction, useGroupHeaderAction: useGroupAction,
      usePresentation, useStore: selector => selector({turnProcesses: []}),
      actions: {setTurnProcessOpen() {}}, cwd: '', openFile() {}, inspectCall() {}, forkAt() {},
      loadImage() {}, renderSlot, renderFactorySlot, t
    };
    const assertRows = () => {
      const rows = [...host.querySelectorAll('[data-chat-flow-key]')];
      if (rows.length !== 100) throw new Error(`chat rows lost after remount: ${rows.length}`);
      let users = 0, assistants = 0;
      rows.forEach((row, i) => {
        const kind = i % 5 === 0 ? 'user' : 'assistant-step';
        if (row.dataset.chatFlowKey !== `node-${i}` || row.dataset.chatNodeKey !== `node-${i}` ||
            row.dataset.chatFlowKind !== kind || row.dataset.chatTurn !== String(i + 1))
          throw new Error(`chat row identity/role mismatch: ${i}`);
        if (!row.textContent.includes(`${kind} message-${i}:`) ||
            !row.textContent.includes(`const message${i} = ${i};`) ||
            !row.textContent.includes('streamed content streamed content'))
          throw new Error(`chat row content missing: ${i}`);
        if (kind === 'user') {
          users++;
          if (!row.querySelector('[data-message-bubble]')) throw new Error(`user bubble missing: ${i}`);
        } else {
          assistants++;
          if (!row.querySelector('.md-code-block')) throw new Error(`assistant code block missing: ${i}`);
          if (row.querySelector('[data-message-bubble]')) throw new Error(`assistant rendered as user: ${i}`);
        }
      });
      if (users !== 20 || assistants !== 80) throw new Error(`role counts: ${users}/${assistants}`);
      if (host.querySelectorAll('[data-streaming]').length !== 1 ||
          !rows[99].querySelector('[data-streaming]') ||
          host.querySelectorAll('[data-chat-running]').length !== 1)
        throw new Error('streaming code/status placeholder missing');
      return {flowRows: rows.length, users, assistants, streamingRows: 1};
    };
    const frame = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const samples = [], rowChecks = [];
    let app, htmlBytes;
    try {
      for (let i = 0; i < 6; i++) {
        app = ReactDOM.createRoot(host);
        const started = performance.now();
        app.render(React.createElement(ChatFlow, props));
        await frame();
        samples.push(performance.now() - started);
        rowChecks.push(assertRows());
        htmlBytes = host.innerHTML.length;
        // 少行负控直接通过真实组件少传一个 entry；不是手工增删 DOM 冒充渲染。
        if (i === 5) {
          app.render(React.createElement(ChatFlow, {...props, entries: entries.slice(0, -1)}));
          await frame();
          let caught = false;
          try { assertRows(); } catch (error) {
            if (error.message !== 'chat rows lost after remount: 99') throw error;
            caught = true;
          }
          if (!caught) throw new Error('Missing-row negative control was not detected');
        }
        app.unmount();
        app = undefined;
        if (host.childNodes.length !== 0) throw new Error(`chat unmount leaked DOM: ${i}`);
      }
      return {samples, flowRows: rowChecks.at(-1).flowRows, htmlBytes, rowChecks, mountCycles: 6, unmountCycles: 6,
        negativeControls: {missingRow: 'caught 99/100', legacyComponent: 'ChatNodeList undefined'}};
    } finally {
      app?.unmount();
      host.remove();
    }
  });
  assertNoErrors(errors);
  const budget = Number(process.env.DSHA_CHAT_RENDER_BUDGET_MS || 3000);
  if (!Number.isFinite(budget) || budget <= 0) throw new Error('Invalid render budget');
  if (Math.max(...result.samples) > budget)
    throw new Error(`100-message mount exceeded ${budget}ms: ${result.samples.join(', ')}`);
  console.log(JSON.stringify({runtime, scenario: '100 messages; code/stream placeholders; 6 mount/unmount cycles',
    budget, ...result, errorNegativeControl: 'caught'}, null, 2));
} finally { await fixture.close(); }
