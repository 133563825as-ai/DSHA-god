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
// 本轮特批的临时滚动生命周期差异，不伪装上游字节或升级上游锁。
const scrollOriginalSource = `const SWAP_RESTORE_MS = 10_000

type SavedScroll = { top: number; savedAt: number; url: string }
const scrollWindow = window as Window & {
  __dshaMobileScrollState?: WeakMap<HTMLElement, SavedScroll>
  /** Last known conversation offset, independent of the scroller's identity. */
  __dshaMobileLastScroll?: SavedScroll
}
const scrollState = scrollWindow.__dshaMobileScrollState instanceof WeakMap
  ? scrollWindow.__dshaMobileScrollState
  : (scrollWindow.__dshaMobileScrollState = new WeakMap<HTMLElement, SavedScroll>())

// 宿主自己写在会话滚动区上的 [data-conversation-scroll] 打头（2026-11-04 热换后对话弹到顶，内置 3.2.2 同样）：
// effect 按后注册先清理（cordis clear() 反序），frame 标记归后注册的 reconciler 所有、先被摘掉，
// 只靠它的话卸载时 0 命中、什么也没存下。
function conversationScrollers(): HTMLElement[] {
  return [...document.querySelectorAll<HTMLElement>('[data-conversation-scroll], [data-mobile-nav="frame"] [class*="scrollBody"]')]
}

/**
 * Where the conversation was scrolled to, independent of the element.
 *
 * The per-element WeakMap below only survives a stylesheet swap, where the
 * scroll body keeps its identity. A hot swap that re-renders the tree (the
 * host re-imports the client module) replaces the node, and the frame marker
 * the scroller query keys on is only applied by a reconciler task on the NEXT
 * frame — so the old restore found nothing and the conversation stayed at 0.
 * Field data 2026-10-30 (phone beacon): convTop 5546 -> 0 within one second of
 * a plugin re-apply, with no pointer or key event in between; the owner's
 * 「时不时跳到上文很久以前的内容」 is exactly that.
 */
function rememberConversationScroll(): void {
  for (const element of conversationScrollers()) {
    if (element.scrollTop > 0) {
      const saved = { top: element.scrollTop, savedAt: Date.now(), url: location.href }
      scrollState.set(element, saved)
      scrollWindow.__dshaMobileLastScroll = saved
    } else scrollState.delete(element)
  }
}

function restoreConversationScroll(): void {
  // Same node (stylesheet-only swap): the cheap path.
  for (const element of conversationScrollers()) {
    const saved = scrollState.get(element)
    scrollState.delete(element)
    if (!saved || Date.now() - saved.savedAt > SWAP_RESTORE_MS || saved.url !== location.href) continue
    requestAnimationFrame(() => {
      if (element.isConnected && saved.url === location.href &&
        Date.now() - saved.savedAt <= SWAP_RESTORE_MS && element.scrollTop === 0)
        element.scrollTop = saved.top
    })
  }
  // Remounted tree (plugin re-apply): retry until the new scroller exists, has
  // content, and the frame marker is in place. Bounded, and it never fights a
  // scroll the user started — it only writes while the scroller sits at 0.
  const saved = scrollWindow.__dshaMobileLastScroll
  if (saved === undefined) return
  let tries = 0
  const attempt = (): void => {
    tries += 1
    if (saved.url !== location.href || Date.now() - saved.savedAt > SWAP_RESTORE_MS) return
    const element = document.querySelector<HTMLElement>('[class*="scrollBody"]')
    if (element !== null && element.scrollTop === 0 && element.scrollHeight >= saved.top + element.clientHeight) {
      element.scrollTop = saved.top
      return
    }
    if (tries < 30) requestAnimationFrame(attempt)
  }
  requestAnimationFrame(attempt)
}
`;
const scrollOriginalBundle = `const SWAP_RESTORE_MS = 10_000;
const scrollWindow = window;
const scrollState = scrollWindow.__dshaMobileScrollState instanceof WeakMap
    ? scrollWindow.__dshaMobileScrollState
    : (scrollWindow.__dshaMobileScrollState = new WeakMap());
// 宿主自己写在会话滚动区上的 [data-conversation-scroll] 打头（2026-11-04 热换后对话弹到顶，内置 3.2.2 同样）：
// effect 按后注册先清理（cordis clear() 反序），frame 标记归后注册的 reconciler 所有、先被摘掉，
// 只靠它的话卸载时 0 命中、什么也没存下。
function conversationScrollers() {
    return [...document.querySelectorAll('[data-conversation-scroll], [data-mobile-nav="frame"] [class*="scrollBody"]')];
}
/**
 * Where the conversation was scrolled to, independent of the element.
 *
 * The per-element WeakMap below only survives a stylesheet swap, where the
 * scroll body keeps its identity. A hot swap that re-renders the tree (the
 * host re-imports the client module) replaces the node, and the frame marker
 * the scroller query keys on is only applied by a reconciler task on the NEXT
 * frame — so the old restore found nothing and the conversation stayed at 0.
 * Field data 2026-10-30 (phone beacon): convTop 5546 -> 0 within one second of
 * a plugin re-apply, with no pointer or key event in between; the owner's
 * 「时不时跳到上文很久以前的内容」 is exactly that.
 */
function rememberConversationScroll() {
    for (const element of conversationScrollers()) {
        if (element.scrollTop > 0) {
            const saved = { top: element.scrollTop, savedAt: Date.now(), url: location.href };
            scrollState.set(element, saved);
            scrollWindow.__dshaMobileLastScroll = saved;
        }
        else
            scrollState.delete(element);
    }
}
function restoreConversationScroll() {
    // Same node (stylesheet-only swap): the cheap path.
    for (const element of conversationScrollers()) {
        const saved = scrollState.get(element);
        scrollState.delete(element);
        if (!saved || Date.now() - saved.savedAt > SWAP_RESTORE_MS || saved.url !== location.href)
            continue;
        requestAnimationFrame(() => {
            if (element.isConnected && saved.url === location.href &&
                Date.now() - saved.savedAt <= SWAP_RESTORE_MS && element.scrollTop === 0)
                element.scrollTop = saved.top;
        });
    }
    // Remounted tree (plugin re-apply): retry until the new scroller exists, has
    // content, and the frame marker is in place. Bounded, and it never fights a
    // scroll the user started — it only writes while the scroller sits at 0.
    const saved = scrollWindow.__dshaMobileLastScroll;
    if (saved === undefined)
        return;
    let tries = 0;
    const attempt = () => {
        tries += 1;
        if (saved.url !== location.href || Date.now() - saved.savedAt > SWAP_RESTORE_MS)
            return;
        const element = document.querySelector('[class*="scrollBody"]');
        if (element !== null && element.scrollTop === 0 && element.scrollHeight >= saved.top + element.clientHeight) {
            element.scrollTop = saved.top;
            return;
        }
        if (tries < 30)
            requestAnimationFrame(attempt);
    };
    requestAnimationFrame(attempt);
}`;
const scrollCanonical = `// DSHA_SCROLL_LIFECYCLE_V1 — 用户2026-11-15批准的临时修复。
// why: 旧RAF保存400后跨热换覆盖新100；until: 上游等价owner/token退休修复经同矩阵验证后删除。
// 首次旧版RAF不认识本token，不能追溯取消；完整保证从fresh load/修复版重载开始。
const SWAP_RESTORE_MS = 10_000
const scrollWindow = window
const scrollState = scrollWindow.__dshaMobileScrollState instanceof WeakMap
  ? scrollWindow.__dshaMobileScrollState
  : (scrollWindow.__dshaMobileScrollState = new WeakMap())
const scrollLifecycle = scrollWindow.__dshaMobileScrollLifecycleV1 ??= {}
let scrollOwner = { disposed: false }
scrollLifecycle.owner ??= scrollOwner
function conversationScrollers() {
  return [...document.querySelectorAll('[data-conversation-scroll], [data-mobile-nav="frame"] [class*="scrollBody"]')]
}
function primaryScroller(elements) {
  const explicit = elements.filter(element => element.matches('[data-conversation-scroll]'))
  return explicit.length === 1 ? explicit[0] : explicit.length === 0 && elements.length === 1 ? elements[0] : undefined
}
function rememberConversationScroll() {
  if (scrollOwner.disposed || scrollLifecycle.owner !== scrollOwner) return
  scrollLifecycle.request?.cancel()
  const elements = conversationScrollers()
  const primary = primaryScroller(elements)
  for (const element of elements) {
    if (element.scrollTop > 0) {
      const saved = { top: element.scrollTop, savedAt: Date.now(), url: location.href }
      scrollState.set(element, saved)
      if (element === primary) scrollWindow.__dshaMobileLastScroll = saved
    } else {
      scrollState.delete(element)
      if (element === primary) delete scrollWindow.__dshaMobileLastScroll
    }
  }
  if (elements.length > 1 && primary === undefined) delete scrollWindow.__dshaMobileLastScroll
}
function restoreConversationScroll() {
  scrollLifecycle.request?.cancel()
  const owner = scrollOwner = { disposed: false }
  scrollLifecycle.owner = owner
  const frames = new Set()
  const request = {
    active: true,
    cancel() {
      request.active = false
      for (const frame of frames) cancelAnimationFrame(frame)
      frames.clear()
    },
  }
  scrollLifecycle.request = request
  const current = () => !owner.disposed && request.active && scrollLifecycle.owner === owner && scrollLifecycle.request === request
  const schedule = (callback) => {
    if (!current()) return
    const frame = requestAnimationFrame(() => {
      frames.delete(frame)
      if (current()) callback()
    })
    frames.add(frame)
  }
  const fresh = (saved) => saved.url === location.href && Date.now() - saved.savedAt <= SWAP_RESTORE_MS
  const retired = new WeakSet()
  for (const element of conversationScrollers()) {
    const saved = scrollState.get(element)
    scrollState.delete(element)
    if (!saved || !fresh(saved)) continue
    schedule(() => {
      if (retired.has(element) || !element.isConnected || !fresh(saved)) { retired.add(element); return }
      if (element.scrollTop !== 0) { retired.add(element); return }
      if (current() && element.scrollHeight >= saved.top + element.clientHeight) { retired.add(element); element.scrollTop = saved.top }
    })
  }
  const saved = scrollWindow.__dshaMobileLastScroll
  let tries = 0
  const attempt = () => {
    if (!current() || !saved) return
    if (!fresh(saved)) { request.cancel(); return }
    tries += 1
    const elements = conversationScrollers()
    const element = primaryScroller(elements)
    if (elements.length > 1 && element === undefined) { request.cancel(); return }
    if (element !== undefined && element.isConnected) {
      if (retired.has(element) || element.scrollTop !== 0) { retired.add(element); request.cancel(); return }
      if (element.scrollHeight >= saved.top + element.clientHeight) {
        if (current()) element.scrollTop = saved.top
        request.cancel()
        return
      }
    }
    if (tries < 30 && current()) schedule(attempt)
    else request.cancel()
  }
  if (saved !== undefined) schedule(attempt)
  return () => {
    if (owner.disposed) return
    if (scrollLifecycle.owner === owner) {
      rememberConversationScroll()
      delete scrollLifecycle.owner
    }
    request.cancel()
    owner.disposed = true
  }
}
`;
export function mobileScrollLifecycleCode(typed = false) {
  if (!typed) return scrollCanonical;
  return scrollCanonical
    .replace('const scrollWindow = window', `type SavedScroll = { top: number; savedAt: number; url: string }
type ScrollRequest = { active: boolean; cancel(): void }
const scrollWindow = window as Window & {
  __dshaMobileScrollState?: WeakMap<HTMLElement, SavedScroll>
  __dshaMobileLastScroll?: SavedScroll
  __dshaMobileScrollLifecycleV1?: { owner?: { disposed: boolean }; request?: ScrollRequest }
}`)
    .replace('new WeakMap()', 'new WeakMap<HTMLElement, SavedScroll>()')
    .replace('function conversationScrollers()', 'function conversationScrollers(): HTMLElement[]')
    .replace("document.querySelectorAll('[data-conversation-scroll]", "document.querySelectorAll<HTMLElement>('[data-conversation-scroll]")
    .replace('function primaryScroller(elements)', 'function primaryScroller(elements: HTMLElement[]): HTMLElement | undefined')
    .replace('function rememberConversationScroll()', 'function rememberConversationScroll(): void')
    .replace('function restoreConversationScroll()', 'function restoreConversationScroll(): () => void')
    .replace('const frames = new Set()', 'const frames = new Set<number>()')
    .replace('const schedule = (callback) =>', 'const schedule = (callback: () => void): void =>')
    .replace('const fresh = (saved) =>', 'const fresh = (saved: SavedScroll): boolean =>')
    .replace('const retired = new WeakSet()', 'const retired = new WeakSet<HTMLElement>()');
}
function scrollReplace(source, before, after) {
  if (source.split(before).length !== 2) throw Error('MOBILE_SCROLL_ANCHOR');
  return source.replace(before, after);
}
export function applyMobileScrollLifecycle(source, typed = false, reverse = false) {
  const old = typed ? scrollOriginalSource : scrollOriginalBundle;
  const next = mobileScrollLifecycleCode(typed);
  source = scrollReplace(source, reverse ? next : old, reverse ? old : next);
  const before = typed ? '    restoreConversationScroll()\n    return () => {\n      rememberConversationScroll()' : '        restoreConversationScroll();\n        return () => {\n            rememberConversationScroll();';
  const after = typed ? '    const disposeScroll = restoreConversationScroll()\n    return () => {\n      disposeScroll()' : '        const disposeScroll = restoreConversationScroll();\n        return () => {\n            disposeScroll();';
  return scrollReplace(source, reverse ? after : before, reverse ? before : after);
}

export function applyMobileClientPatches(bytes) {
  if (sha(bytes) !== upstreamClientHash) throw new Error('上游 client 字节不匹配固定 commit；请重新审阅补丁');
  let source = bytes.toString('utf8');
  // 多语言补丁的 dispose 锚点必须看到上游原文；齿轮 owner 会在 dispose 开头插入
  // retirement 守卫，所以先落多语言，再落齿轮差异。
  source = applyMobileLocaleBundlePatch(source);
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('补丁锚点缺失或不唯一: ' + before.slice(0, 100));
    source = source.replace(before, after);
  };
  // build336（T101 重做，用户选 C）+ build351（2026-11-09 同步上游 3.3.5）：以上游为基座，
  // client 保留四类既有差异及2026-11-15特批临时滚动生命周期修复：
  //   1) 宿主集成 DSHA_GEAR_FIT_V1 + DSHA_GEAR_SINGLETON_V1：footer 齿轮按实测行宽让位
  //      （上游 3.3.5 仍是旧的「连接条 :has() 存在性」判据，DSH 0.2.2 首连不渲染指示灯时会判错），
  //      并且同一行只保留一颗齿轮（还原 / 热重载 / 重复注册可能让插件同时存在多个实例）；
  //   2) 安全区：applyMobileSafeArea 把 env(safe-area-inset-*) 包成 DSHA 原生发布的变量；
  //   3) 文件传输：applyFileTransferClient（原生 share / save-as / 下载记录与 32 MiB 限制）；
  //   4) 多语言：applyMobileLocaleBundlePatch（zh-Hant/ru/ja/ko/ar/pt/la/fr + live-locale）。
  // 会话输入 V4、手机搜索、快捷键弹层可见带、字体族行换行、遮罩按压染色按「完全追随上游」
  // 删除；残留问题、上游覆盖情况与测试变更见 docs/web-mobile-patches.md 与 build336/build351 记录。
  // DSHA_GEAR_FIT_V1（T61）：footer 齿轮的让位判据改成**只量几何**，不再读宿主
  // ConnectionIndicator 是否存在。DSH 0.2.2 首次连接不再渲染指示灯后，上游
  // `:has(> button[data-phase], > [role="status"])` 判据永远「放得下」，手机上这条
  // 行第一次走进「没有指示灯」分支；让位口径改成实测行内剩余宽度，宿主以后增删任何
  // 孩子（指示灯、更新条、新按钮）都自动计入。3.3.1/3.3.5 构建剥了 CSS 注释，锚点改为规则本体。
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
  // DSHA_GEAR_SINGLETON_V1：同一 triggerRow 只保留一颗齿轮。
  // 插件可能因页面在还原后热重载、运行时 profile 暂时重复登记或同一模块被重复应用而
  // 同时存在多个任务实例；每个实例各自持有一个 wrap，全都 append 到同一行时就会看到
  // 两颗、三颗设置齿轮。这里在每次 ensure 挂载前清掉同一行里其它实例的 wrap，只保留
  // 本实例这一颗；晚到的实例会接管，页面上始终最多一颗。只动插件自己的
  // [data-mobile-nav="quick-settings-wrap"]，不碰宿主节点。
  replace(`            if (wrap !== null && wrap.parentElement !== target)
                target.appendChild(wrap);
            bindRowHook();`,
    `            if (wrap !== null) {
                // DSHA_GEAR_SINGLETON_V1：清掉同一行里其它实例遗留的齿轮。
                for (const stale of target.querySelectorAll('[data-mobile-nav="quick-settings-wrap"]')) {
                    if (stale !== wrap) stale.remove();
                }
                if (wrap.parentElement !== target)
                    target.appendChild(wrap);
            }
            bindRowHook();`);
  // DSHA_GEAR_OWNER_V1：每次激活持有独立租期；旧 epoch 的 ensure/迟到回调不再争抢。
  const gearStart = 'function createDrawerQuickSettingsTask(ctx) {';
  const gearEnd = '\n};\n__modules["core/image-zoom-core.js"]';
  if (source.split(gearStart).length !== 2 || source.split(gearEnd).length !== 2) throw Error('MOBILE_GEAR_OWNER_ANCHOR');
  const begin = source.indexOf(gearStart), end = source.indexOf(gearEnd, begin);
  let gear = source.slice(begin, end);
  const gearReplace = (before, after) => {
    if (gear.split(before).length !== 2) throw Error('MOBILE_GEAR_OWNER_ANCHOR: ' + before);
    gear = gear.replace(before, after);
  };
  gearReplace(gearStart, `${gearStart}
    // DSHA_GEAR_OWNER_V1：window 跨模块热换持有唯一 owner。第一个 ensure 取得 token；
    // 当前 owner 退休/清空前其它实例的 ensure 直接返回，不再互删 wrap 触发 childList→RAF 循环。
    const lifecycle = window.__dshaMobileGearOwnerV1 ??= {};
    const owner = { retired: false, claimed: false };
    const current = () => !owner.retired && lifecycle.owner === owner;
    const claim = () => {
        if (owner.retired || owner.claimed) return;
        if (lifecycle.owner !== undefined && !lifecycle.owner.retired) return;
        owner.claimed = true;
        lifecycle.owner = owner;
    };
    const later = (callback, millis) => window.setTimeout(() => { if (current()) callback(); }, millis);
    const frame = (callback) => requestAnimationFrame(() => { if (current()) callback(); });`);
  // 只替换真实函数内的异步派发；clearTimeout 保留原行为。
  gear = gear.replaceAll('window.setTimeout(', 'later(').replaceAll('requestAnimationFrame(', 'frame(')
    .replace('const later = (callback, millis) => later(', 'const later = (callback, millis) => window.setTimeout(')
    .replace('const frame = (callback) => frame(', 'const frame = (callback) => requestAnimationFrame(');
  for (const anchor of ['const onRowPressEnd = () => {', 'const onRowPointerDown = (event) => {',
    'const selectSettingsItem = (trigger) => {', 'const onClick = () => {',
    'const onPressStart = () => {', 'const onPressEnd = () => {'])
    gearReplace(anchor, anchor + '\n        if (!current()) return;');
  gearReplace('const clearPendingMark = () => {', 'const clearPendingMark = () => {\n        if (lifecycle.owner !== owner) return;');
  gearReplace('    return {\n        name: \'drawer-quick-settings\',', '    const task = {\n        name: \'drawer-quick-settings\',');
  gearReplace('        ensure: () => {', '        ensure: () => {\n            claim();\n            if (!current()) return;');
  // 账户行是宿主节点，dispose 不能仅清 timer，也不能重新查询后误清新行。
  gearReplace('    let rowPressedAt = 0;', '    let rowPressedAt = 0;\n    let pressedRow = null;');
  gearReplace(`    const pressRow = (on) => {
        const target = row();
        if (target === null)
            return;
        if (on)
            target.setAttribute(ROW_PRESSED_ATTR, '');
        else
            target.removeAttribute(ROW_PRESSED_ATTR);
    };`, `    const pressRow = (on) => {
        if (lifecycle.owner !== owner) return;
        if (!on) {
            pressedRow?.removeAttribute(ROW_PRESSED_ATTR);
            pressedRow = null;
            return;
        }
        const target = row();
        if (target === null) return;
        if (pressedRow !== target) pressedRow?.removeAttribute(ROW_PRESSED_ATTR);
        pressedRow = target;
        target.setAttribute(ROW_PRESSED_ATTR, '');
    };`);
  gearReplace('        dispose: () => {', `        dispose: () => {
            if (owner.retired) return;
            owner.retired = true;
            pressRow(false);
            press(false);`);
  gearReplace("            document.documentElement.removeAttribute('data-mobile-nav-quick');\n            clearMenuEntry();", `            if (lifecycle.owner === owner) {
                document.documentElement.removeAttribute('data-mobile-nav-quick');
                clearMenuEntry();
                delete lifecycle.owner;
            }`);
  gearReplace('    };\n}', '    };\n    return task;\n    }\n}');
  gearReplace(gearStart, `${gearStart}
    // reconciler 的 deactivate 会 dispose，activate 会复用同一 registered task。
    // 仅退休本次租期，不退休 task；新租期重建闭包，使旧 timer/RAF/监听永远绑定旧 token。
    let activation = null;
    return {
        name: 'drawer-quick-settings',
        scopes: ['*'],
        ensure: () => {
            activation ??= createGearActivation();
            activation.ensure();
        },
        dispose: () => {
            const previous = activation;
            activation = null;
            previous?.dispose();
        },
    };
    function createGearActivation() {`);
  source = source.slice(0, begin) + gear + source.slice(end);
  // tablet adapter 的 observer 在 task 外排 RAF；task 必须支持 core 再激活，
  // 因此由实际 effect 租期封印旧 run，而不是把 stable task 永久退休。
  replace(`        let queued = false;
        const run = () => {
            queued = false;
            try {
                task.ensure();`, `        let alive = true;
        let queued = false;
        let pendingFrame = null;
        const run = () => {
            if (!alive) return;
            pendingFrame = null;
            queued = false;
            try {
                task.ensure();`);
  replace(`        const observer = new MutationObserver(() => {
            if (queued)
                return;
            queued = true;
            requestAnimationFrame(run);
        });`, `        const observer = new MutationObserver(() => {
            if (!alive || queued)
                return;
            queued = true;
            pendingFrame = requestAnimationFrame(run);
        });`);
  replace(`        return () => {
            observer.disconnect();
            task.dispose();
        };`, `        return () => {
            if (!alive) return;
            alive = false;
            observer.disconnect();
            if (pendingFrame !== null) cancelAnimationFrame(pendingFrame);
            pendingFrame = null;
            queued = false;
            task.dispose();
        };`);
  return applyMobileScrollLifecycle(applyMobileSafeArea(applyFileTransferClient(source)));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [input, action = '--check'] = process.argv.slice(2);
  if (input === '--locale-sources') {
    const [directory, localeAction = '--check'] = process.argv.slice(3);
    if (!directory || !['--check', '--write'].includes(localeAction)) throw new Error('用法: node tools/apply-mobile-client-patches.mjs --locale-sources <固定上游目录> [--check|--write]');
    for (const name of mobileLocaleSourceFiles) {
      const bytes = readFileSync(path.join(directory, name));
      if (sha(bytes) !== lock.files[name]?.sha256) throw new Error('mobile locale source differs from fixed commit: ' + name);
      const localized = applyMobileLocaleSourcePatch(name, bytes.toString('utf8'));
      const patched = name === 'src/client/index.tsx' ? applyMobileScrollLifecycle(localized, true) : localized;
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
