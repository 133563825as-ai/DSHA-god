import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { MobileNavToggle } from './components/MobileNavToggle.tsx'
import { ComposerFileButton } from './components/ComposerFileButton.tsx'
import { openFilesPanel } from './components/open-files-panel.ts'
import { MOBILE_CSS } from './styles/index.ts'

import { installFrameController, installPhoneChrome } from './effects/phone-chrome.ts'
import { installReconciler, registerReconcileTasks } from './effects/reconciler-adapter.ts'
import { installSelectionChromeYield } from './effects/selection-chrome.ts'
import { installOverlayInteractions, toggleDrawer } from './effects/drawer-close.ts'
import { installSidebarSwipe } from './effects/sidebar-swipe.ts'
import { installSubagentChipTouch } from './effects/subagent-chip-touch.ts'
import { installSessionMenuDelete } from './effects/session-menu.ts'
import { installComposerKeyboardGuard } from './effects/composer-keyboard-guard.ts'
import { installComposerKeyboardLift } from './effects/composer-keyboard-lift.ts'
import { installComposerClearance } from './effects/composer-clearance.ts'
import { installDocumentScrollLock } from './effects/document-scroll-lock.ts'
import { installQuestionFullscreen } from './effects/question-fullscreen.ts'
import { installComposerPlusToggle } from './effects/composer-plus-toggle.ts'
import { installWorkspaceChipToggle } from './effects/workspace-chip-toggle.ts'
import { installTeamChipToggle } from './effects/team-chip-toggle.ts'
import { installModelMenuAnchor } from './effects/model-menu-anchor.ts'
import { installShortcutModalKeyboardGuard } from './effects/shortcut-modal-keyboard-guard.ts'
import { installModelMenuKeyboardGuard } from './effects/model-menu-keyboard-guard.ts'
import { installPluginCardTap } from './effects/plugin-card-tap.ts'
import { installInstalledListRows } from './effects/installed-list.ts'
import { installTabletQuickSettings } from './effects/tablet-quick-settings.ts'
import { installAionuiCompat } from './effects/aionui-compat.ts'
import { installComposerPasteGuard } from './effects/composer-paste-guard.ts'
import { installComposerEnterNewline } from './effects/composer-enter-newline.ts'
import { installFocusGestureGuard } from './effects/focus-gesture-guard.ts'
import { installComposerFilePicker } from './effects/composer-file-picker.ts'
import { installAttachmentMention } from './effects/attachment-mention.ts'
import { installFileShare } from './effects/file-share.ts'
import { createPanelExit, installPanelRowExit } from './effects/panel-exit.ts'
import { installDebugBadge } from './debug.ts'
import { NS, mobileNavLocales } from './i18n/locales.ts'
import type { MobileNavKey } from './i18n/locales.ts'

declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface LocaleNamespaceMap {
    /** Directory-drawer controls copy. */
    'dshWebMobileNav': MobileNavKey
  }
}

/**
 * Required services (cordis fiber inject — the loader passes all module exports
 * as an object plugin). `inject` is a HARD dependency: a missing service stops
 * the whole plugin from loading, so nothing may be listed here that the client
 * does not actually read (issue #86 removed the never-read `workspaces`).
 */
export const inject = ['slots', 'layout', 'locale', 'sessionLogDownload', 'sessions']

/**
 * How long a remembered scroll position stays usable (see {@link restoreConversationScroll}).
 * The gap between our teardown and re-apply is one dynamic import inside the host's
 * client-modules queue — well under a second in practice; ten is generous.
 */
// DSHA_SCROLL_LIFECYCLE_V1 — 用户2026-11-15批准的临时修复。
// why: 旧RAF保存400后跨热换覆盖新100；until: 上游等价owner/token退休修复经同矩阵验证后删除。
// 首次旧版RAF不认识本token，不能追溯取消；完整保证从fresh load/修复版重载开始。
const SWAP_RESTORE_MS = 10_000
type SavedScroll = { top: number; savedAt: number; url: string }
type ScrollRequest = { active: boolean; cancel(): void }
const scrollWindow = window as Window & {
  __dshaMobileScrollState?: WeakMap<HTMLElement, SavedScroll>
  __dshaMobileLastScroll?: SavedScroll
  __dshaMobileScrollLifecycleV1?: { owner?: { disposed: boolean }; request?: ScrollRequest }
}
const scrollState = scrollWindow.__dshaMobileScrollState instanceof WeakMap
  ? scrollWindow.__dshaMobileScrollState
  : (scrollWindow.__dshaMobileScrollState = new WeakMap<HTMLElement, SavedScroll>())
const scrollLifecycle = scrollWindow.__dshaMobileScrollLifecycleV1 ??= {}
let scrollOwner = { disposed: false }
scrollLifecycle.owner ??= scrollOwner
function conversationScrollers(): HTMLElement[] {
  return [...document.querySelectorAll<HTMLElement>('[data-conversation-scroll], [data-mobile-nav="frame"] [class*="scrollBody"]')]
}
function primaryScroller(elements: HTMLElement[]): HTMLElement | undefined {
  const explicit = elements.filter(element => element.matches('[data-conversation-scroll]'))
  return explicit.length === 1 ? explicit[0] : explicit.length === 0 && elements.length === 1 ? elements[0] : undefined
}
function rememberConversationScroll(): void {
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
function restoreConversationScroll(): () => void {
  scrollLifecycle.request?.cancel()
  const owner = scrollOwner = { disposed: false }
  scrollLifecycle.owner = owner
  const frames = new Set<number>()
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
  const schedule = (callback: () => void): void => {
    if (!current()) return
    const frame = requestAnimationFrame(() => {
      frames.delete(frame)
      if (current()) callback()
    })
    frames.add(frame)
  }
  const fresh = (saved: SavedScroll): boolean => saved.url === location.href && Date.now() - saved.savedAt <= SWAP_RESTORE_MS
  const retired = new WeakSet<HTMLElement>()
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

/**
 * Mobile-adaptive shell, browser half: injects the mobile stylesheet, then
 * contributes the directory toggle to the session header and the backdrop +
 * floating button to the shell overlay.
 * @param ctx - client root context.
 */
export function apply(ctx: ClientContext): void {
  ctx.effect(() => ctx.locale.register(NS, mobileNavLocales), 'dsh-web-mobile: dictionaries')

  ctx.effect(() => {
    // 先清掉可能残留的同 id 样式表。
    // 2026-09-23：app 的 P8 自愈会把 client.js 换回上游版、页面里也随之挂着
    // **上游那份 CSS**；我们重新部署产物后，只 append 不清旧的话，页面里那份旧表
    // 仍然压在上面（实测：开关/权限图标/头部留白改完"看着没生效"）。
    // 本插件是原地热重载（不整页刷新），所以这一步必须自己保证"页面里的样式表
    // 就是当前产物里的这一份"。
    for (const stale of document.querySelectorAll('style[data-plugin-css="dsh-web-mobile/mobile.css"]')) {
      stale.remove()
    }
    const tag = document.createElement('style')
    tag.dataset.plugin = 'dsh-web-mobile'
    tag.dataset.pluginCss = 'dsh-web-mobile/mobile.css'
    tag.textContent = MOBILE_CSS
    document.head.appendChild(tag)
    // Keep this stylesheet last in <head> so its overrides win over the
    // host UI's own styles (some host rules also use !important).
    setTimeout(() => {
      if (tag.isConnected) document.head.appendChild(tag)
    }, 0)
    // 热换后的滚动回位（2026-10-07 店主：「聊到一半突然闪到最上面」）。
    // 宿主热换插件走 client-modules.replace()：tearDown → import → refresh，
    // 我们这张表在窗口里会**整个消失**，长会话的 content-visibility 估算与布局
    // 翻转会让 Chromium 把会话滚动区重锚到顶部。卸载前记下位置、重挂后回填。
    const disposeScroll = restoreConversationScroll()
    return () => {
      disposeScroll()
      tag.remove()
    }
  }, 'dsh-web-mobile: styles')

  // Market「installed」列表行的内联样式：实现与全部注释见 effects/installed-list.ts。
  installInstalledListRows(ctx)

  // 宽屏触屏（平板 ≥1024）：只补一颗一键设置齿轮，布局仍交给宿主原生。
  installTabletQuickSettings(ctx)


  // Leaving a sidebar panel. The host's panels replace the main area and ship
  // no way back, so every exit route (system back, re-tapping the selected
  // panel row, the FAB) shares this one action.
  const panelExit = createPanelExit(ctx.layout)

  // Shared mobile infrastructure: frame marker ownership and the single
  // full-tree reconciler. Installed inside one effect so a plugin reload in
  // the same JS environment tears the whole reconciler down and rebuilds it.
  ctx.effect(() => {
    const stops = [
      installFrameController(),
      installReconciler(ctx),
      registerReconcileTasks(ctx, panelExit),
      // Last: stop the tasks before releasing the exit instance they drive.
      () => panelExit.dispose(),
    ]
    return () => {
      for (const stop of stops) stop()
    }
  }, 'dsh-web-mobile: reconciler infrastructure')

  // Selection handle drag: while a conversation selection is live the header drops
  // out of the hit test, so the native extent stays local instead of snapping to the
  // flow's first item and revealing it (teleporting to the top of the session).
  installSelectionChromeYield(ctx)



  // Drawer close interactions: Escape and navigation taps inside the drawer.
  installOverlayInteractions(ctx)

  // Sidebar panel exit: re-tapping the already-selected panel row returns to
  // the conversation (the system-back route is a reconciler task; both call the
  // same action).
  installPanelRowExit(ctx, panelExit.exit)

  // Session deletion, injected into each session row's ⋯ menu (beside
  // rename / fork / archive) with a confirm dialog. Mobile-only.
  installSessionMenuDelete(ctx)

  // Sidebar swipe gestures: edge swipe-in opens the drawer, content swipe-out
  // closes it (release-classified, zero inline transforms — A 档). Since
  // 2026-09-13 the layer also owns the right-edge files gesture (leftward
  // opens the files panel via openFilesPanel, rightward closes whatever is
  // on top — the panel or the drawer); a leftward stroke never collapses
  // anything.
  installSidebarSwipe(ctx, openFilesPanel)

  // Lineage-count chip: reliable open/close on touch pointers (upstream is
  // hover-timer driven and has no onClick on the count variant).
  installSubagentChipTouch(ctx)

  // iOS: tapping the composer's send/stop/+ buttons must not re-raise the
  // dismissed keyboard (upstream keepFocus focuses the editor on mousedown).
  installComposerKeyboardGuard(ctx)
  // iOS: the host Lexical scroll helper mis-scrolls the window on every
  // keystroke (issue #149); pin the composer seat above the keyboard.
  // 2026-10-31: 键盘抬升不武装。帧数据显示它和引擎自己的滚动纠正互相拉扯
  // （seat 578→283→560，conv 8572↔8587 每帧来回），而引擎单独工作时座位是落在
  // 可见带内的。实现原样保留在 effects/composer-keyboard-lift.ts，改回一行即可重开。
  void installComposerKeyboardLift
  // Keyboard-shrunken landscape (issue #245): the sticky composer seat's editing
  // row lands inside the opaque, z-index-30 conversation header, so the input
  // box is covered until the page reloads. Measure the band and let the CSS
  // outrank the header while it is covered.
  installComposerClearance(ctx)
  installDocumentScrollLock(ctx)
  // Ask-question card (owner report 2026-10-25): a manual expand/collapse control
  // in the card header turns the answer area into the whole visible band
  // (the host's 60vh ceiling collapses with the keyboard), and the answer field
  // is scrolled back into the card body when it is covered.
  installQuestionFullscreen(ctx)
  installComposerPlusToggle(ctx)
  // Hero workspace chip: the host's picker portaled its Menu with
  // `anchor={null}`, so its own outside-pointerdown close eats the trigger's
  // tap and the chip's toggle re-opens it. Swallow that one click.
  installWorkspaceChipToggle(ctx)
  // Agent Team chip: the host trigger only opens (its onClick focuses the panel
  // when open, never toggles), so a second tap could not close it. Dispatch the
  // outside pointerdown its own dismiss hook waits for, then swallow the click.
  installTeamChipToggle(ctx)
  // Model/reasoning menu portals to <body>; the CSS centering rule died with the
  // portal move, so re-anchor it on the trigger here (owner report: opens far left).
  installModelMenuAnchor(ctx)
  // Shortcut modal (settings → 通用设置 → 快捷键): the host focuses its search
  // field on open, which raises the soft keyboard over a page the user came to
  // EDIT, and the keyboard shrinking the viewport resizes the sheet (owner
  // report: 「打开的时候还是会闪，而且还会唤起键盘」).
  installShortcutModalKeyboardGuard(ctx)
  // Model / reasoning-level menu (owner report 2026-10-07): drilling into the
  // model pane focuses the host's 「搜索模型…」 field from a passive effect, so the
  // keyboard covers the list the user just opened. Same method-shadow cure,
  // armed from the capture-phase tap that precedes the pane switch.
  installModelMenuKeyboardGuard(ctx)
  installPluginCardTap(ctx)

  // Multi-line paste after an IME commit keeps only the first line (host
  // Lexical routes Android's insertText paste through the text-insertion
  // command). Re-dispatch those as a paste event; mobile-only.
  installComposerPasteGuard(ctx)

  // Soft-keyboard Enter inserts a newline instead of sending (owner 2026-11-05):
  // phone keyboards have no Shift, so the host's Enter-to-send left no way to
  // break a line. Send stays on the button; Ctrl/Cmd+Enter still sends.
  installComposerEnterNewline(ctx)

  // Only a real tap on a text field may raise the soft keyboard (owner
  // 2026-11-05): every host flow that focuses a field programmatically (mount
  // autoFocus, returning from a subpage, switching tabs) otherwise pops the IME
  // over the page.
  installFocusGestureGuard(ctx)

  // Composer paperclip (owner report 2026-10-06): the system file picker is the
  // wrong shape and cannot pick "images only", so the tap opens the plugin's own
  // two-option sheet (上传图片 / 上传附件) and hands the choice back to the host's
  // hidden input. Intake stays fully host-owned.
  installComposerFilePicker(ctx)

  // @ menu 「本次附件」: mention the composer's draft attachments as locked chips.
  installAttachmentMention(ctx)

  installPhoneChrome(ctx)

  installAionuiCompat(ctx)

  // Mobile file sharing: Files-tree row buttons + preview header button
  // (official 0.2.0 slots), share sheet with download fallback.
  installFileShare(ctx)

  // Debug badge (?mobile-nav-debug=1): live state overlay for phone-side
  // repros. No-op without the query param (docs: README, AGENTS.md).
  installDebugBadge(ctx)

  ctx.slots.inject('conversation.session.header.actions', () => ctx.slots.register({
    name: 'conversation.session.header.actions',
    id: 'mobile-nav-toggle',
    order: 10,
    locale: NS,
    inject: () => ({
      toggleSidebar: () => toggleDrawer(ctx),
    }),
  }, MobileNavToggle))

  // Session log download lives in the drawer brand row as an icon (issue #225):
  // the reconciler task in effects/drawer-session-log.ts injects it there, so
  // the drawer footer no longer carries a plugin row.

  // Composer file entry (0.1.6 host): the host deleted the paperclip attach
  // button, leaving the 「文件」row inside the "+" listbox as the only file
  // entry. Re-add a permanent one in the host's own conversation.input.left
  // seat (inside the tools lane, beside the plus button). It triggers the
  // host's hidden input[type=file] — the same fileInputRef.current.click()
  // the host's own command runs — so intake validation, upload and the
  // availability policy stay host-owned.
  ctx.slots.inject('conversation.input.left', () => ctx.slots.register({
    name: 'conversation.input.left',
    id: 'mobile-nav-file-upload',
    order: 10,
    locale: NS,
    inject: () => ({}),

  }, ComposerFileButton))
}

// Type-only augmentation imports: pull the layout / conversation / sidebar /
// settings SlotMap merges and the sessions service typing into this program
// without any runtime import (the sessionLogDownload augmentation lives in
// effects/drawer-session-log.ts, which reads that service).
import type {} from '@deepseek-ai/dsh-client-ui-layout/client'
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'
import type {} from '@deepseek-ai/dsh-client-locale/client'