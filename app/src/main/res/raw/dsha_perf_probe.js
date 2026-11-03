// DSHA 测试包性能采样（网页侧）。只在测试包、只在用户点“性能采样”后由原生 evaluateJavascript 注入；
// 结果由原生调用 window.__dshaPerfProbe.stop() 取回 JSON 字符串，不经任何 JavascriptInterface。
(function (root) {
  'use strict';
  if (root.__dshaPerfProbe && root.__dshaPerfProbe.version === 1) return;

  var MAX_SAMPLES = 20000;
  var TOP_SOURCES = 30;
  var TOP_FUNCTIONS = 5;
  var WORST_FRAMES = 10;
  var MUTATION_BUCKET_MS = 100;
  var MAX_MUTATION_BUCKETS = 600;

  function round(value, digits) {
    var scale = Math.pow(10, digits == null ? 2 : digits);
    return Math.round(value * scale) / scale;
  }

  // 最近秩法，与原生 FrameStats 一致。
  function percentile(sorted, p) {
    if (!sorted.length) return 0;
    var rank = Math.ceil((p / 100) * sorted.length);
    return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
  }

  function distribution(values) {
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var sum = 0;
    for (var i = 0; i < sorted.length; i++) sum += sorted[i];
    return {
      count: sorted.length,
      mean: sorted.length ? round(sum / sorted.length) : 0,
      p50: round(percentile(sorted, 50)),
      p90: round(percentile(sorted, 90)),
      p95: round(percentile(sorted, 95)),
      p99: round(percentile(sorted, 99)),
      max: sorted.length ? round(sorted[sorted.length - 1]) : 0
    };
  }

  function rafSummary(intervals, budget) {
    var result = distribution(intervals);
    var longLimit = budget * 1.5;
    var long = 0, over2 = 0, total = 0;
    for (var i = 0; i < intervals.length; i++) {
      total += intervals[i];
      if (intervals[i] > longLimit) long++;
      if (intervals[i] > budget * 2) over2++;
    }
    result.budgetMs = round(budget, 3);
    result.longIntervals = long;
    result.over2xIntervals = over2;
    result.fps = total > 0 ? round((intervals.length * 1000) / total) : 0;
    return result;
  }

  // 把脚本 URL 归到来源：DSH 本体、某个插件、合并请求（??a,b）里包含的插件，或页面外来源。
  function classifySource(url) {
    if (!url) return { key: '(inline/unknown)', mobile: false };
    var original = String(url);
    var injected = /^dsha-injected\/([^/?#]+)$/.exec(original);
    if (injected) return { key: 'app:' + injected[1], mobile: false, appInjected: true };
    var path = original;
    try { var parsed = new URL(path, root.location && root.location.href); path = parsed.pathname + (parsed.search || ''); } catch (e) {}
    var combo = path.indexOf('??');
    if (combo >= 0) {
      var parts = path.slice(combo + 2).split(',');
      var names = [];
      for (var i = 0; i < parts.length; i++) {
        var match = /(?:^|\/)(@[^/]+\/[^/]+|[^/@][^/]*)\/(?:lib|dist|src|client)/.exec(parts[i]);
        var name = match ? match[1] : parts[i].split('/')[0];
        if (name && names.indexOf(name) < 0) names.push(name);
      }
      var joined = names.join(',');
      return { key: 'combo:' + joined.slice(0, 200), mobile: joined.indexOf('dsh-web-mobile') >= 0, combo: true };
    }
    var plugin = /\/plugins\/((?:@[^/]+\/)?[^/?#]+)/.exec(path);
    if (plugin) return { key: 'plugin:' + plugin[1], mobile: plugin[1].indexOf('dsh-web-mobile') >= 0 };
    if (/^(?:https?:)?\/\//.test(String(url)) && root.location && String(url).indexOf(root.location.origin) !== 0)
      return { key: 'external:' + String(url).slice(0, 120), mobile: false };
    if (/^(?:chrome-extension|blob|data):/.test(String(url))) return { key: String(url).split(':')[0] + ':', mobile: false };
    return { key: 'dsh:' + path.replace(/[?#].*$/, '').slice(0, 160), mobile: false };
  }

  function addSource(map, url, fn, invoker, duration, forced) {
    var source = classifySource(url);
    var row = map[source.key];
    if (!row) row = map[source.key] = { source: source.key, mobile: source.mobile, combo: !!source.combo, count: 0, totalMs: 0, maxMs: 0, forcedLayoutMs: 0, functions: {} };
    row.count++;
    row.totalMs += duration;
    row.maxMs = Math.max(row.maxMs, duration);
    row.forcedLayoutMs += forced || 0;
    var fnKey = (fn || '(anonymous)') + (invoker ? ' ← ' + String(invoker).slice(0, 80) : '');
    var f = row.functions[fnKey] || (row.functions[fnKey] = { name: fnKey, count: 0, totalMs: 0 });
    f.count++;
    f.totalMs += duration;
  }

  function finishSources(map) {
    var rows = [];
    for (var key in map) {
      if (!Object.prototype.hasOwnProperty.call(map, key)) continue;
      var row = map[key];
      var fns = [];
      for (var name in row.functions) if (Object.prototype.hasOwnProperty.call(row.functions, name)) fns.push(row.functions[name]);
      fns.sort(function (a, b) { return b.totalMs - a.totalMs; });
      rows.push({
        source: row.source, mobile: row.mobile, combo: row.combo, count: row.count,
        totalMs: round(row.totalMs, 1), maxMs: round(row.maxMs, 1), forcedLayoutMs: round(row.forcedLayoutMs, 1),
        topFunctions: fns.slice(0, TOP_FUNCTIONS).map(function (f) { return { name: f.name, count: f.count, totalMs: round(f.totalMs, 1) }; })
      });
    }
    rows.sort(function (a, b) { return b.totalMs - a.totalMs; });
    return rows.slice(0, TOP_SOURCES);
  }

  function summarizeLoaf(entries) {
    var durations = [], blocking = [], sources = {}, worst = [];
    var scriptMs = 0, styleLayoutMs = 0, renderMs = 0, mobileMs = 0, dshMs = 0, appInjectedMs = 0, otherMs = 0;
    for (var i = 0; i < entries.length; i++) {
      var e = entries[i];
      durations.push(e.duration || 0);
      blocking.push(e.blockingDuration || 0);
      var end = (e.startTime || 0) + (e.duration || 0);
      if (e.renderStart > 0) renderMs += Math.max(0, end - e.renderStart);
      if (e.styleAndLayoutStart > 0) styleLayoutMs += Math.max(0, end - e.styleAndLayoutStart);
      var scripts = e.scripts || [];
      for (var j = 0; j < scripts.length; j++) {
        var s = scripts[j];
        var d = s.duration || 0;
        scriptMs += d;
        addSource(sources, s.sourceURL, s.sourceFunctionName, s.invoker, d, s.forcedStyleAndLayoutDuration);
        var c = classifySource(s.sourceURL);
        if (c.mobile) mobileMs += d;
        else if (c.appInjected) appInjectedMs += d;
        else if (c.key.indexOf('dsh:') === 0) dshMs += d;
        else otherMs += d;
      }
      worst.push(e);
    }
    worst.sort(function (a, b) { return (b.duration || 0) - (a.duration || 0); });
    return {
      count: entries.length,
      duration: distribution(durations),
      blocking: distribution(blocking),
      totals: {
        scriptMs: round(scriptMs, 1), styleAndLayoutMs: round(styleLayoutMs, 1), renderMs: round(renderMs, 1),
        mobilePluginScriptMs: round(mobileMs, 1), dshScriptMs: round(dshMs, 1), appInjectedScriptMs: round(appInjectedMs, 1), otherScriptMs: round(otherMs, 1)
      },
      bySource: finishSources(sources),
      worst: worst.slice(0, WORST_FRAMES).map(function (e) {
        return {
          startTime: round(e.startTime || 0, 1), duration: round(e.duration || 0, 1),
          blockingDuration: round(e.blockingDuration || 0, 1),
          scripts: (e.scripts || []).slice(0, 5).map(function (s) {
            return { source: classifySource(s.sourceURL).key, fn: s.sourceFunctionName || '', invoker: String(s.invoker || '').slice(0, 80), duration: round(s.duration || 0, 1) };
          })
        };
      })
    };
  }

  function summarizeLongtasks(entries) {
    var durations = [], containers = {};
    for (var i = 0; i < entries.length; i++) {
      var e = entries[i];
      durations.push(e.duration || 0);
      var attr = (e.attribution && e.attribution[0]) || {};
      var key = (attr.containerType || 'window') + ':' + (attr.containerSrc || attr.containerName || attr.name || 'self');
      var row = containers[key] || (containers[key] = { container: key, count: 0, totalMs: 0 });
      row.count++;
      row.totalMs += e.duration || 0;
    }
    var rows = [];
    for (var k in containers) if (Object.prototype.hasOwnProperty.call(containers, k)) rows.push({ container: k, count: containers[k].count, totalMs: round(containers[k].totalMs, 1) });
    rows.sort(function (a, b) { return b.totalMs - a.totalMs; });
    return { count: entries.length, duration: distribution(durations), byContainer: rows.slice(0, TOP_SOURCES) };
  }

  function summarizeEvents(entries) {
    var byName = {}, delays = [], durations = [];
    for (var i = 0; i < entries.length; i++) {
      var e = entries[i];
      var delay = Math.max(0, (e.processingStart || 0) - (e.startTime || 0));
      var processing = Math.max(0, (e.processingEnd || 0) - (e.processingStart || 0));
      delays.push(delay);
      durations.push(e.duration || 0);
      var row = byName[e.name] || (byName[e.name] = { delay: [], processing: [], duration: [] });
      row.delay.push(delay);
      row.processing.push(processing);
      row.duration.push(e.duration || 0);
    }
    var names = {};
    for (var name in byName) {
      if (!Object.prototype.hasOwnProperty.call(byName, name)) continue;
      names[name] = { inputDelay: distribution(byName[name].delay), processing: distribution(byName[name].processing), duration: distribution(byName[name].duration) };
    }
    return { count: entries.length, inputDelay: distribution(delays), duration: distribution(durations), byName: names };
  }

  function detectMobile(doc, perf) {
    var evidence = [];
    var urls = [];
    try {
      var nodes = doc.querySelectorAll('script[src],link[href]');
      for (var i = 0; i < nodes.length; i++) urls.push(nodes[i].getAttribute('src') || nodes[i].getAttribute('href') || '');
    } catch (e) {}
    try {
      var resources = perf.getEntriesByType ? perf.getEntriesByType('resource') : [];
      for (var j = 0; j < resources.length; j++) urls.push(resources[j].name || '');
    } catch (e) {}
    for (var k = 0; k < urls.length; k++) {
      if (/\/plugins\//.test(urls[k]) && urls[k].indexOf('dsh-web-mobile') >= 0) {
        evidence.push('module:' + classifySource(urls[k]).key);
        break;
      }
    }
    try {
      if (doc.querySelector('[data-mobile-nav]')) evidence.push('dom:data-mobile-nav');
      var html = doc.documentElement;
      if (html && html.style && html.style.getPropertyValue && html.style.getPropertyValue('--dsh-web-mobile-vh')) evidence.push('css:--dsh-web-mobile-vh');
    } catch (e) {}
    return { loaded: evidence.length > 0, evidence: evidence };
  }

  function chromeVersion(ua) {
    var match = /Chrome\/([\d.]+)/.exec(ua || '');
    return match ? match[1] : null;
  }

  function createProbe(env) {
    var perf = env.performance;
    var state = null;

    function supported() {
      var types = (env.PerformanceObserver && env.PerformanceObserver.supportedEntryTypes) || [];
      return {
        longAnimationFrame: types.indexOf('long-animation-frame') >= 0,
        longtask: types.indexOf('longtask') >= 0,
        event: types.indexOf('event') >= 0,
        entryTypes: Array.prototype.slice.call(types)
      };
    }

    function observe(type, options, sink) {
      if (!env.PerformanceObserver) return null;
      try {
        var observer = new env.PerformanceObserver(function (list) {
          var items = list.getEntries();
          for (var i = 0; i < items.length && sink.length < MAX_SAMPLES; i++) sink.push(items[i]);
        });
        var opts = { type: type };
        for (var key in options) if (Object.prototype.hasOwnProperty.call(options, key)) opts[key] = options[key];
        observer.__dshaSink = sink;
        observer.observe(opts);
        return observer;
      } catch (e) {
        state.errors.push(type + ': ' + (e && e.message));
        return null;
      }
    }

    function frame(time) {
      if (!state || !state.running) return;
      if (state.lastRaf != null && state.raf.length < MAX_SAMPLES) state.raf.push(time - state.lastRaf);
      state.lastRaf = time;
      state.rafHandle = env.requestAnimationFrame(frame);
    }

    function start(budgetMs) {
      if (state && state.running) return 'already-running';
      var budget = budgetMs > 0 ? budgetMs : 1000 / 60;
      var support = supported();
      state = {
        running: true, budget: budget, startedAt: perf.now(), raf: [], lastRaf: null,
        loaf: [], longtask: [], events: [], observers: [], errors: [], support: support,
        domNodesAtStart: countNodes(), mutationStartPerfNow: perf.now(), mutationTimeOrigin: perf.timeOrigin,
        mutationCounts: [], mutationTotal: 0, mutationObserver: null,
        resizeWindow: 0, resizeVisualViewport: 0, innerHeightChanges: [], resizeBucketCounts: []
      };
      state.lastInnerHeight = env.innerHeight || 0;
      state.onWindowResize = function () {
        if (!state || !state.running) return;
        state.resizeWindow++;
        var h = env.innerHeight || 0;
        if (h !== state.lastInnerHeight) {
          state.lastInnerHeight = h;
          if (state.innerHeightChanges.length < 200)
            state.innerHeightChanges.push({ t: round(perf.now() - state.startedAt, 1), h: h });
        }
        var bucket = Math.max(0, Math.floor((perf.now() - state.startedAt) / 1000));
        while (state.resizeBucketCounts.length <= bucket) state.resizeBucketCounts.push({ t: bucket, window: 0, visualViewport: 0 });
        state.resizeBucketCounts[bucket].window++;
      };
      state.onVisualViewportResize = function () {
        if (!state || !state.running) return;
        state.resizeVisualViewport++;
        var bucket = Math.max(0, Math.floor((perf.now() - state.startedAt) / 1000));
        while (state.resizeBucketCounts.length <= bucket) state.resizeBucketCounts.push({ t: bucket, window: 0, visualViewport: 0 });
        state.resizeBucketCounts[bucket].visualViewport++;
      };
      if (env.addEventListener) {
        try { env.addEventListener('resize', state.onWindowResize, { passive: true }); }
        catch (e) { env.addEventListener('resize', state.onWindowResize); }
      }
      if (env.visualViewport && env.visualViewport.addEventListener) {
        try { env.visualViewport.addEventListener('resize', state.onVisualViewportResize, { passive: true }); }
        catch (e) { env.visualViewport.addEventListener('resize', state.onVisualViewportResize); }
      }
      if (env.MutationObserver && env.document && env.document.documentElement) {
        try {
          state.mutationObserver = new env.MutationObserver(function (records) {
            if (!state || !state.running) return;
            var now = perf.now();
            var bucket = Math.max(0, Math.floor((now - state.mutationStartPerfNow) / MUTATION_BUCKET_MS));
            if (bucket >= MAX_MUTATION_BUCKETS) return;
            while (state.mutationCounts.length <= bucket) state.mutationCounts.push(0);
            state.mutationCounts[bucket] += records.length;
            state.mutationTotal += records.length;
          });
          state.mutationObserver.observe(env.document.documentElement, { subtree: true, childList: true, characterData: true });
        } catch (e) {
          state.errors.push('mutation: ' + (e && e.message));
          state.mutationObserver = null;
        }
      }
      if (support.longAnimationFrame) state.observers.push(observe('long-animation-frame', {}, state.loaf));
      else if (support.longtask) state.observers.push(observe('longtask', {}, state.longtask));
      if (support.event) state.observers.push(observe('event', { durationThreshold: 16 }, state.events));
      state.rafHandle = env.requestAnimationFrame(frame);
      return 'started';
    }

    function countNodes() {
      try { return env.document.getElementsByTagName('*').length; } catch (e) { return null; }
    }

    function stop() {
      var result = {
        version: 1, running: false,
        userAgent: (env.navigator && env.navigator.userAgent) || '',
        chromeVersion: chromeVersion(env.navigator && env.navigator.userAgent),
        url: env.location ? String(env.location.pathname || '') : '',
        devicePixelRatio: env.devicePixelRatio || null,
        viewport: { width: env.innerWidth || null, height: env.innerHeight || null },
        visibilityState: env.document ? env.document.visibilityState : null,
        mobile: detectMobile(env.document, perf)
      };
      if (!state) {
        result.error = 'not-started';
        return JSON.stringify(result);
      }
      state.running = false;
      if (env.removeEventListener && state.onWindowResize) env.removeEventListener('resize', state.onWindowResize);
      if (env.visualViewport && env.visualViewport.removeEventListener && state.onVisualViewportResize)
        env.visualViewport.removeEventListener('resize', state.onVisualViewportResize);
      if (state.rafHandle != null && env.cancelAnimationFrame) env.cancelAnimationFrame(state.rafHandle);
      if (state.mutationObserver) {
        try {
          var pendingMutations = state.mutationObserver.takeRecords ? state.mutationObserver.takeRecords() : [];
          if (pendingMutations.length) {
            var mutationNow = perf.now();
            var mutationBucket = Math.max(0, Math.floor((mutationNow - state.mutationStartPerfNow) / MUTATION_BUCKET_MS));
            if (mutationBucket < MAX_MUTATION_BUCKETS) {
              while (state.mutationCounts.length <= mutationBucket) state.mutationCounts.push(0);
              state.mutationCounts[mutationBucket] += pendingMutations.length;
              state.mutationTotal += pendingMutations.length;
            }
          }
          state.mutationObserver.disconnect();
        } catch (e) {}
      }
      for (var i = 0; i < state.observers.length; i++) {
        var observer = state.observers[i];
        if (!observer) continue;
        try {
          var rest = observer.takeRecords ? observer.takeRecords() : [];
          var sink = observer.__dshaSink;
          if (rest && rest.length && sink) for (var r = 0; r < rest.length; r++) sink.push(rest[r]);
          observer.disconnect();
        } catch (e) {}
      }
      result.durationMs = round(perf.now() - state.startedAt, 1);
      result.support = state.support;
      result.domNodes = { start: state.domNodesAtStart, end: countNodes() };
      result.mutations = {
        bucketMs: MUTATION_BUCKET_MS, startPerfNow: round(state.mutationStartPerfNow, 1),
        timeOrigin: state.mutationTimeOrigin || null, counts: state.mutationCounts.slice(0, MAX_MUTATION_BUCKETS),
        total: state.mutationTotal
      };
      result.resize = {
        window: state.resizeWindow, visualViewport: state.resizeVisualViewport,
        innerHeightChanges: state.innerHeightChanges,
        bucketsPer1s: state.resizeBucketCounts
      };
      result.raf = rafSummary(state.raf, state.budget);
      if (state.support.longAnimationFrame) result.longAnimationFrames = summarizeLoaf(state.loaf);
      else if (state.support.longtask) result.longTasks = summarizeLongtasks(state.longtask);
      result.events = summarizeEvents(state.events);
      result.truncated = state.raf.length >= MAX_SAMPLES || state.loaf.length >= MAX_SAMPLES || state.events.length >= MAX_SAMPLES;
      if (state.errors.length) result.errors = state.errors;
      state = null;
      return JSON.stringify(result);
    }

    return { version: 1, start: start, stop: stop, isRunning: function () { return !!(state && state.running); } };
  }

  var exported = {
    percentile: percentile, distribution: distribution, rafSummary: rafSummary, classifySource: classifySource,
    summarizeLoaf: summarizeLoaf, summarizeLongtasks: summarizeLongtasks, summarizeEvents: summarizeEvents,
    detectMobile: detectMobile, chromeVersion: chromeVersion, createProbe: createProbe
  };
  if (typeof module === 'object' && module && module.exports) module.exports = exported;
  if (root && root.performance && root.requestAnimationFrame) {
    var probe = createProbe(root);
    probe.internals = exported;
    root.__dshaPerfProbe = probe;
  }
})(typeof window !== 'undefined' ? window : this);
