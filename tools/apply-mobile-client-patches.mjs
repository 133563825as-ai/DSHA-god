// 从固定上游预构建产物重建 DSHA 差异；不执行上游安装/构建脚本。
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { applyFileTransferClient } from './file-transfer/client-mobile.mjs';
import { applyMobileSafeArea } from './mobile-safe-area.mjs';
import { applyMobileLocaleBundlePatch, applyMobileLocaleSourcePatch, mobileLocaleSourceFiles } from './mobile-locales.mjs';
const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(readFileSync(path.join(project, 'tools/mobile-plugin-upstream.lock.json'), 'utf8'));
export const upstreamCommit = lock.commit;
export const upstreamClientHash = lock.files['lib/client.js'].sha256;
export const upstreamServerHashes = Object.fromEntries(
  ['compress.js', 'delete-session.js', 'index.js'].map(name => [name, lock.files['lib/' + name].sha256]));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const serverAnchors = {
  'compress.js': 'export function installResponseCompression()',
  'delete-session.js': 'export async function deleteSession(deps, sessionId)',
  'index.js': 'export function apply(ctx)',
};
// rc2 鉴权、公开生命周期、原件保留和有界流压缩仍由 DSHA 实现。
export function applyMobileServerPatch(name, bytes) {
  if (!(name in upstreamServerHashes)) throw new Error('unknown mobile server module: ' + name);
  const output = readFileSync(path.join(project, 'tools/mobile-server', name));
  if (bytes.equals(output)) return output;
  if (sha(bytes) !== upstreamServerHashes[name] || bytes.toString('utf8').split(serverAnchors[name]).length !== 2)
    throw new Error('mobile server source differs from fixed commit/anchor: ' + name);
  return output;
}
// canonical 与真实本地来源逐行对应，不能沿用上游 TypeScript 的旧映射。
export function mobileServerSourceMap(name, bytes) {
  const source = bytes.toString('utf8'), lines = source.split('\n').length;
  return JSON.stringify({ version: 3, file: name, sourceRoot: '',
    sources: ['dsha://tools/mobile-server/' + name], sourcesContent: [source], names: [],
    mappings: ['AAAA', ...Array(Math.max(0, lines - 1)).fill('AACA')].join(';') }) + '\n';
}
export function applyMobileClientPatches(bytes) {
  if (sha(bytes) !== upstreamClientHash) throw new Error('上游 client 字节不匹配固定 commit；请重新审阅补丁');
  let source = bytes.toString('utf8');
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('补丁锚点缺失或不唯一: ' + before.slice(0, 100));
    source = source.replace(before, after);
  };
  const replaceRange = (startAnchor, endAnchor, after) => {
    const start = source.indexOf(startAnchor), end = source.indexOf(endAnchor, start + startAnchor.length);
    if (start < 0 || end < 0 || source.split(startAnchor).length !== 2 || source.split(endAnchor).length !== 2)
      throw new Error('补丁模块范围缺失或不唯一: ' + startAnchor);
    source = source.slice(0, start) + after + source.slice(end);
  };
  // 3.3.1（上游 main 4e71eb67，适配宿主 0.2.2）保留 viewport-meta 的可见视口变量与
  // interactive-widget=resizes-content；上游未恢复按帧合并的视口试验，这里也不带回。
  // 按实际鼠标/touch/pen 事件分流；不能把用户点击伪造成键盘 detail=0。
  replaceRange('            // DSHA detail=0 follows the host\'s keyboard/programmatic open path.',
    '            // A touch row-tap owns the close (pointerup or the navigation observer);',
    '            // DSHA_SESSION_INPUT_V4：保留原始点击，实际打开仍由 dsha-session-open 关闭抽屉。\n');
  // 0.2.2 的快捷键弹层仍按 ≤767px 收搜索行；DSHA 保留手机搜索（首次自动聚焦由
  // 0.2.2 的定向守卫拦下，只隐藏 CSS 而不删除宿主 React 节点）。
  replace(`  @media (max-width: 767px) {
    [aria-modal="true"][data-shortcut-modal="shortcuts"] [class*="_searchRow"] {
      display: none !important;
    }
  }`,
    '  /* DSHA 保留手机搜索；首次自动聚焦由 0.2.2 的定向守卫拦下，不删除宿主 React 节点。 */');
  // Documentation-only: V4 opens valid touch/pen/mouse clicks through the host;
  // keep the owned-row exemption and close on the actual open event, not pointerup.
  replace('        // DSHA_SESSION_INTERACTION_V1：宿主把「单击=选中、双击=打开」拆成了两步\n'
    + '        // （data-dsha-session-select 标记 + dsha-session-open 事件）。上游的\n'
    + '        // 「点行即关抽屉」会在第一次单击就把抽屉收掉，双击永远到不了。\n'
    + '        // 这些行改由 dsha-session-open 事件关闭（见下方 document 监听）。',
    '        // DSHA_SESSION_INTERACTION_V4：有效的 touch/pen/mouse 单击由宿主打开会话。\n'
    + '        // data-dsha-session-select 标记的行等待真实 dsha-session-open 事件关闭抽屉。\n'
    + '        // 不在 pointerup 提前收起，避免吞掉浏览器随后派发的原始 click。\n'
    + '        // 关闭依据是宿主已经打开会话的事实（见下方 document 监听）。');
  replace('        /** Swallow the host\'s title double-click rename: on touch a double *tap*\n'
    + '         *  means "open the session" (DSHA\'s own two-tap detection sits on the row\'s\n'
    + '         *  click handler), and letting the browser\'s `dblclick` reach the title would\n'
    + '         *  open the host\'s rename dialog on that same gesture. Capture phase on',
    '        /** Swallow the host\'s title double-click rename: repeated touch taps already\n'
    + '         *  use the host\'s normal session-open path. Letting the browser\'s `dblclick`\n'
    + '         *  reach the title would also open the rename dialog on that gesture.\n'
    + '         *  Keep the original click handling unchanged. Capture phase on');
  replace('        // DSHA 宿主在「真正打开会话」时才派发 dsha-session-open（单击只选中），\n'
    + '        // 所以抽屉的关闭挂在这个事实上，而不是挂在点击上。',
    '        // DSHA V4 有效单击打开会话后派发 dsha-session-open；\n'
    + '        // 抽屉只在打开事实到达时关闭，保留原始 click 与行标记豁免。');
  // 3.3.1 的整页遮罩是 div（不是 button），0.2.2 之后通用 :active inset 染色不再落到
  // 遮罩上；旧的 DSHA_BACKDROP_PRESS_V1 没有可修的对象，已随本次同步删除。
  // 宽屏触屏档收起归档/置顶已由上游 3.3.1 自带（712999a / PR #257，规则落在
  // (min-width: 1024px) and (pointer: coarse) 档，名单与手机壳一致），不再重放。
  //
  // 0.2.2 起手机档设置弹层交回宿主官方全屏；DSHA 只给快捷键弹层按可见带收口。
  // 原生已消费 IME/systemBars，这里只读 DSHA 发布的实际浏览器视口（viewport-meta）。
  const css = `
  /* DSHA 可见区域边界：原生已消费 IME/systemBars，只读浏览器实际视口。 */
  [aria-modal="true"][data-shortcut-modal="shortcuts"] {
    top: calc(env(safe-area-inset-top, 0px) + 12px + var(--dsha-mobile-viewport-top, 0px)) !important;
    max-height: max(1px, calc(var(--dsha-mobile-visible-vh, 100vh) - 24px - env(safe-area-inset-top, 0px))) !important;
    min-height: 0 !important;
    box-sizing: border-box;
    overflow-y: auto;
    transition: none;
  }
  [aria-modal="true"][data-shortcut-modal="shortcuts"] > :first-child {
    min-height: 0;
    max-height: 100%;
  }

  /* @patch
     id: font-family-row-wrap-rc5
     for: dsh-client-ui-theme 0.2.2-rc.1 的 FontFamilyRow（FontSizeRow.module.css 的
          .bVCLcG_row / .bVCLcG_rowText，加 primitives Input 的 .vnGTiq_field）
     why: 宿主的 .row 是不换行 flex、.field 固定 flex:0 240px；手机设置弹层内容宽约 340px，
          标题+说明列因此被压成约 1 字/行、弹层被纵向撑高。固定上游 3.3.1（4e71eb67）的
          client 里仍没有任何 FontFamilyRow / rowText 选择器，暂无上游修复。
     until: 上游给字体行手机档换行排版，或宿主把 .field 固定宽改成可收缩——届时删本块
     test: tools/test-mobile-client-patches.mjs
  */
  /* DSHA_FONT_FAMILY_ROW_V1：0.2.2 的 Input 原语把 <input> 包在 span[class*="_wrap"] 里，
     240px 与 spellcheck/autocomplete/maxlength 也落在 wrapper 内层，因此同时覆盖
     「裸 input」与「wrapper 内 input」两种形态；只命中设置弹层里带 FontFamilyRow 那组
     属性的输入框行，字号行是 stepper、没有 input，天然不命中。:has 保持单层。 */
  @media (max-width: 1023px) and (pointer: coarse) {
    [aria-modal="true"][data-shortcut-modal="settings"] div[class*="_row"]:has(> input[spellcheck="false"][autocomplete="off"][maxlength="200"]) {
      flex-wrap: wrap !important;
      row-gap: 8px !important;
    }
    [aria-modal="true"][data-shortcut-modal="settings"] div[class*="_row"]:has(> span[class*="_wrap"] > input[spellcheck="false"][autocomplete="off"][maxlength="200"]) {
      flex-wrap: wrap !important;
      row-gap: 8px !important;
    }
    [aria-modal="true"][data-shortcut-modal="settings"] div[class*="_row"]:has(> input[spellcheck="false"][autocomplete="off"][maxlength="200"]) > div[class*="_rowText"],
    [aria-modal="true"][data-shortcut-modal="settings"] div[class*="_row"]:has(> span[class*="_wrap"] > input[spellcheck="false"][autocomplete="off"][maxlength="200"]) > div[class*="_rowText"] {
      flex: 1 1 100% !important;
      padding-right: 0 !important;
    }
    [aria-modal="true"][data-shortcut-modal="settings"] div[class*="_row"]:has(> input[spellcheck="false"][autocomplete="off"][maxlength="200"]) > input,
    [aria-modal="true"][data-shortcut-modal="settings"] div[class*="_row"]:has(> span[class*="_wrap"] > input[spellcheck="false"][autocomplete="off"][maxlength="200"]) > span[class*="_wrap"] {
      flex: 1 1 100% !important;
      width: 100% !important;
      box-sizing: border-box;
    }
  }
`;
  // 3.3.1 构建剥了 CSS 注释，锚点改为规则本体；DSHA 覆盖紧跟在宿主规则之后。
  const modalRule = `  [aria-modal="true"][data-shortcut-modal="shortcuts"] {
    position: absolute !important;
    left: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 12px) !important;
    width: calc(100vw - 16px) !important;
    max-width: calc(100vw - 16px) !important;
    max-height: min(760px, calc(var(--dsh-web-mobile-vh, 100dvh) - 24px - env(safe-area-inset-top, 0px))) !important;
    transition: max-height .2s var(--ds-ease-out, ease-in-out);
    transform: none !important;
    border-radius: 14px !important;
    animation: none !important;
  }`;
  replace(modalRule, modalRule + '\n' + css);
  // DSHA_GEAR_FIT_V1（T61）：footer 齿轮的让位判据改成**只量几何**，不再读宿主
  // ConnectionIndicator 是否存在。DSH 0.2.2 首次连接不再渲染指示灯后，上游
  // `:has(> button[data-phase], > [role="status"])` 判据永远「放得下」，手机上这条
  // 行第一次走进「没有指示灯」分支；让位口径改成实测行内剩余宽度，宿主以后增删任何
  // 孩子（指示灯、更新条、新按钮）都自动计入。3.3.1 构建剥了 CSS 注释，锚点改为规则本体。
  const gearGuardRule = `[data-mobile-nav="frame"] [class*="sidebarCol"] [class*="triggerRow"]:has(> button[data-phase], > [role="status"]) [data-mobile-nav="quick-settings-wrap"] {
  display: none !important;
}`;
  replace(gearGuardRule,
    `/* @patch
   id: drawer-quick-settings-fit-guard
   for: 侧栏 footer 的 triggerRow（账户 launcher + 宿主连接条 / 更新条 / 以后新增的孩子）
   why: 该行只有 260px；连接条 flex:none（中文约 136px、英文约 190px），齿轮常驻会在英文下把账户按钮挤到约 26px、头像被裁。旧守卫用 :has(> button[data-phase], > [role="status"]) 依赖宿主指示灯**存在**：DSH 0.2.2 首次连接不再渲染指示灯后判据失灵。改为由 effects/drawer-quick-settings.ts 实测行内剩余宽度，把结论写在本插件自己的属性上——齿轮可见性不再依赖宿主任何子节点的存在。
   until: 宿主给 settings.launcher 提供独立的次要动作位——届时删本块
   test: tools/test-mobile-gear-contract.mjs
*/
[data-mobile-nav="frame"] [class*="sidebarCol"] [class*="triggerRow"] [data-mobile-nav="quick-settings-wrap"][data-mobile-nav-quick-fit="0"] {
  display: none !important;
}`);
  // DSHA_GEAR_FIT_V1：纯几何判据（导出给宿主测试，见 tools/test-mobile-gear-contract.mjs）。
  replace('function createDrawerQuickSettingsTask(ctx) {',
    `/** DSHA_GEAR_FIT_V1：齿轮占位＝按钮 30px + 右外边距 6px + 4px 余量（见上方 CSS）。 */
const GEAR_SLOT_PX = 40;
/**
 * DSHA_GEAR_FIT_V1：齿轮此刻放不放得下。
 *
 * 旧判据读「行的直接孩子里有没有宿主的连接条（button[data-phase] / [role="status"]）」，
 * 于是插件的渲染依赖宿主那个节点**存在**：宿主改了首次连接的渲染（DSH 0.2.2 首连不再
 * 显示指示灯）之后，判据永远得到「放得下」，行的真实宽度却没人核对。这里只核对几何：
 * 行内容宽度 − 其它可见孩子宽度 ≥ 齿轮占位；宿主以后增删任何孩子都自动被计入。
 *
 * @param rowWidth 行内容宽度（已扣左右 padding，CSS px）
 * @param usedWidth 其它可见孩子的宽度和（含外边距，CSS px）
 * @returns 放得下为 true；宽度不可用（≤ 0 或非有限数）按「放不下」处理，保守保留账户菜单入口
 */
function dshaGearFits(rowWidth, usedWidth) {
  return Number.isFinite(rowWidth) && Number.isFinite(usedWidth)
    && rowWidth > 0 && usedWidth >= 0 && rowWidth - usedWidth >= GEAR_SLOT_PX;
}
exports.dshaGearFits = dshaGearFits;
exports.GEAR_SLOT_PX = GEAR_SLOT_PX;
function createDrawerQuickSettingsTask(ctx) {`);
  replace(`    /**
     * 齿轮此刻在不在场：唯一会隐藏它的是连接条守卫（\`drawer-quick-settings-chip-guard\`），
     * 条件就是行的**直接孩子**里有连接条（\`button[data-phase]\` / \`[role="status"]\`）。
     * 这里镜像同一判据而不是读计算样式——两处口径必须一致，改守卫时一起改。
     */
    const gearAvailable = (target) => ![...target.children].some((child) => child.getAttribute('data-phase') !== null || child.getAttribute('role') === 'status');`,
    `    /**
     * DSHA_GEAR_FIT_V1：实测这一行还剩多少宽度。
     *
     * 只量几何、不看宿主节点的身份：连接条、更新条、以后新增的任何孩子都按宽度计入。
     * 布局还没就绪（行宽 ≈ 0）时 dshaGearFits 返回 false，即保守地保留账户菜单入口。
     */
    const gearAvailable = (target) => {
        const style = getComputedStyle(target);
        const rowWidth = target.getBoundingClientRect().width
            - (Number.parseFloat(style.paddingLeft) || 0)
            - (Number.parseFloat(style.paddingRight) || 0);
        let usedWidth = 0;
        for (const child of target.children) {
            if (child === wrap) continue;
            const childStyle = getComputedStyle(child);
            if (childStyle.display === 'none' || childStyle.visibility === 'hidden') continue;
            usedWidth += child.getBoundingClientRect().width
                + (Number.parseFloat(childStyle.marginLeft) || 0)
                + (Number.parseFloat(childStyle.marginRight) || 0);
        }
        return dshaGearFits(rowWidth, usedWidth);
    };`);
  replace('    const syncMenuEntry = (target) => {',
    '    const syncMenuEntry = (target, gearVisible) => {');
  replace("        const keep = trigger !== null && trigger.getAttribute('aria-expanded') === 'true' && gearAvailable(target);",
    "        const keep = trigger !== null && trigger.getAttribute('aria-expanded') === 'true' && gearVisible;");
  replace(`            if (wrap !== null && wrap.parentElement !== target)
                target.appendChild(wrap);
            bindRowHook();
            syncMenuEntry(target);`,
    `            if (wrap !== null && wrap.parentElement !== target)
                target.appendChild(wrap);
            bindRowHook();
            // DSHA_GEAR_FIT_V1：可见性只由实测宽度决定，结论写在插件自己的属性上；
            // 幂等写入（值没变就不写），避免自己的 mutation 再把任务唤醒一轮。
            const gearVisible = gearAvailable(target);
            if (wrap !== null) {
                const fit = gearVisible ? '1' : '0';
                if (wrap.dataset.mobileNavQuickFit !== fit) wrap.dataset.mobileNavQuickFit = fit;
            }
            syncMenuEntry(target, gearVisible);`);
  return applyMobileSafeArea(applyFileTransferClient(applyMobileLocaleBundlePatch(source)));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [input, action = '--check'] = process.argv.slice(2);
  if (input === '--locale-sources') {
    const [directory, localeAction = '--check'] = process.argv.slice(3);
    if (!directory || !['--check', '--write'].includes(localeAction)) throw new Error('用法: node tools/apply-mobile-client-patches.mjs --locale-sources <固定上游目录> [--check|--write]');
    for (const name of mobileLocaleSourceFiles) {
      const bytes = readFileSync(path.join(directory, name));
      if (sha(bytes) !== lock.files[name]?.sha256) throw new Error('mobile locale source differs from fixed commit: ' + name);
      const patched = applyMobileLocaleSourcePatch(name, bytes.toString('utf8'));
      const output = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile', name);
      if (localeAction === '--write') writeFileSync(output, patched);
      else if (readFileSync(output, 'utf8') !== patched) throw new Error('移动语言源码与锁定补丁不符: ' + name);
    }
    console.log('移动语言源码与上游指纹核验通过: ' + upstreamCommit);
    process.exit(0);
  }
  if (input === '--server') {
    const [directory, serverAction = '--check'] = process.argv.slice(3);
    if (!directory || !['--check', '--write'].includes(serverAction)) throw new Error('用法: node tools/apply-mobile-client-patches.mjs --server <固定上游lib目录> [--check|--write]');
    for (const name of Object.keys(upstreamServerHashes)) {
      const patched = applyMobileServerPatch(name, readFileSync(path.join(directory, name)));
      const output = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib', name);
      const map = mobileServerSourceMap(name, patched);
      if (serverAction === '--write') { writeFileSync(output, patched); writeFileSync(output + '.map', map); }
      else if (!readFileSync(output).equals(patched) || readFileSync(output + '.map', 'utf8') !== map)
        throw new Error('移动服务产物/映射与锁定补丁不符: ' + name);
    }
    // New DSHA-only module has no upstream member: copy only the reviewed
    // canonical source, and retain the same exact-byte check as other modules.
    const transfer = readFileSync(path.join(project, 'tools/file-transfer/file-transfer.js'));
    const transferOutput = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/file-transfer.js');
    const transferMap = JSON.parse(mobileServerSourceMap('file-transfer.js', transfer));
    transferMap.sources = ['dsha://tools/file-transfer/file-transfer.js'];
    const transferMapText = JSON.stringify(transferMap) + '\n';
    if (serverAction === '--write') { writeFileSync(transferOutput, transfer); writeFileSync(transferOutput + '.map', transferMapText); }
    else if (!readFileSync(transferOutput).equals(transfer) || readFileSync(transferOutput + '.map', 'utf8') !== transferMapText)
      throw new Error('文件传输服务产物/映射与canonical不符');
    console.log('移动服务补丁与上游指纹核验通过: ' + upstreamCommit);
    process.exit(0);
  }
  if (!input || !['--check', '--write'].includes(action)) throw new Error('用法: node tools/apply-mobile-client-patches.mjs <上游lib/client.js> [--check|--write]');
  const patched = applyMobileClientPatches(readFileSync(input));
  const output = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js');
  if (action === '--write') writeFileSync(output, patched);
  else if (readFileSync(output, 'utf8') !== patched) throw new Error('当前内置产物与锁定上游及 DSHA 补丁不符');
  console.log('移动插件补丁与上游指纹核验通过: ' + upstreamCommit);
}
