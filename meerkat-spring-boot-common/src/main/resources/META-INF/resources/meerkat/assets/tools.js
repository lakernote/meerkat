import {
  $, authHeaders, bearerToken, csrfToken, initializeEmbeddedAuth, loginRedirected, node, SESSION_EXPIRED_MESSAGE, showNotice
} from './common.js?v=20261111.4'

const API = new URL('../../api/meerkat/tools/', import.meta.url)
const DENIED = t('没有查看的权限，请登录或检查 ObservabilityAccessPolicy')
const OPERATE_DENIED = t('没有敏感操作的权限，请检查 ObservabilityAccessPolicy.canOperate')
const DISABLED = t('工具箱未启用：请设置 meerkat.tools.enabled=true 后重启应用')
const ROW_HEIGHT = 18
const TABS = ['log-levels', 'profiler', 'heap', 'heap-dump', 'config', 'classes']
let status = null
let overrides = []
let countdown = null
let profileTimer = null
let profile = null
let flameFocus = null
let flameRects = []
let tab = 'log-levels'
let loggerSearchTimer = null
let loggerRequest = 0
let statusRequest = 0
let statusFailed = false
/** Names from the root to the frame chosen with the keyboard, or null. */
let flameCursor = null

/** Calls the toolbox API; {@code messages} maps HTTP statuses to the text shown when they are returned. */
async function call(path, options = {}, messages = {}) {
  const method = options.method || 'GET'
  const headers = authHeaders(method, options.json ? { 'Content-Type': 'application/json' } : {})
  const response = await fetch(new URL(path, API), {
    method, headers, credentials: 'same-origin', body: options.json ? JSON.stringify(options.json) : undefined
  })
  if (!response.ok) {
    const message = messages[response.status]
      || (response.status === 401 ? DENIED : response.status === 403 ? (options.operate ? OPERATE_DENIED : DENIED)
        : response.status === 404 && status && !status.enabled ? DISABLED
          : t('请求失败（HTTP {status}）', { status: response.status }))
    throw new Error(message)
  }
  if (response.status !== 204 && loginRedirected(response)) throw new Error(SESSION_EXPIRED_MESSAGE)
  if (options.raw) return response
  return response.status === 204 ? null : response.json()
}

async function download(response, fallback) {
  const name = /filename="?([^";]+)"?/.exec(response.headers.get('Content-Disposition') || '')?.[1] || fallback
  const url = URL.createObjectURL(await response.blob())
  const link = node('a')
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function guarded(button, action) {
  if (button) button.disabled = true
  try {
    showNotice('')
    await action()
  } catch (error) {
    showNotice(error.message, true)
  } finally {
    if (button) button.disabled = locked(button)
  }
}

function locked(button) {
  if (button.classList.contains('operate') && !status?.canOperate) return true
  return button.id === 'profile-start' && profile?.session.state === 'RUNNING'
}

function formatBytes(bytes) {
  const value = Math.abs(bytes)
  if (value >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`
  if (value >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  if (value >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function signed(value, format = String) {
  if (value == null) return '—'
  return value > 0 ? '+' + format(value) : value < 0 ? '-' + format(-value) : '0'
}

function time(value) {
  return value ? new Date(value).toLocaleTimeString(MeerkatI18n.locale) : '—'
}

function cell(text, className) {
  return node('td', className, text == null ? '—' : String(text))
}

function fact(container, label, value) {
  const item = node('span', null, t('{label}：', { label }))
  item.append(node('b', null, String(value)))
  container.append(item)
}

function table(headers, rows) {
  const wrap = node('div', 'table-scroll')
  const element = node('table', 'stats-table live-table tools-table')
  const head = node('tr')
  headers.forEach(header => head.append(node('th', header.numeric ? 'numeric' : null, header.label ?? header)))
  const body = node('tbody')
  rows.forEach(row => body.append(row))
  element.append(node('thead'), body)
  element.firstChild.append(head)
  wrap.append(element)
  return wrap
}

// ---- status ----

async function refresh() {
  const requestId = ++statusRequest
  let next
  try {
    next = await call('status')
  } catch (error) {
    if (requestId !== statusRequest) return
    statusFailed = true
    showNotice(error.message, true)
    return
  }
  // A slower, older response must not overwrite permissions from a newer one.
  if (requestId !== statusRequest) return
  status = next
  if (statusFailed) {
    statusFailed = false
    showNotice('')
  }
  if (!status.enabled) {
    showNotice(DISABLED, true)
    document.querySelectorAll('.tools-panel button').forEach(button => { button.disabled = true })
    return
  }
  $('operate-denied').hidden = status.canOperate
  document.querySelectorAll('button.operate').forEach(button => {
    button.disabled = locked(button)
    button.title = status.canOperate ? '' : OPERATE_DENIED
  })
  $('logger-level').replaceChildren(...status.logLevels.map(level => node('option', null, level)))
  if (status.logLevels.includes('DEBUG')) $('logger-level').value = 'DEBUG'
  $('logger-duration').querySelectorAll('option').forEach(option => {
    option.disabled = Number(option.value) > status.maxLogLevelSeconds
  })
  if ($('logger-duration').selectedOptions[0]?.disabled) {
    const allowed = [...$('logger-duration').options].filter(option => !option.disabled)
    if (allowed.length) $('logger-duration').value = allowed[allowed.length - 1].value
  }
  $('histogram-baseline').textContent = status.histogramBaseline ? t('已有上次快照，本次将与其对比') : t('首次采集将作为对比基线')
  await loadLoggers()
  if (status.profile && !profile) await loadProfile(status.profile.id)
}

// ---- log levels ----

async function loadLoggers() {
  if (!status.loggingSystem) {
    $('loggers-summary').hidden = false
    $('loggers-summary').textContent = t('没有可用的 Spring Boot 日志系统')
    return
  }
  const query = $('logger-query').value.trim()
  const requestId = ++loggerRequest
  const result = await call(`loggers?limit=200&q=${encodeURIComponent(query)}`)
  if (requestId !== loggerRequest) return
  overrides = result.overrides
  renderOverrides()
  const loggers = result.loggers.loggers
  $('logger-rows').replaceChildren(...loggers.map(logger => {
    const row = node('tr', logger.overriddenUntil ? 'is-selected' : null)
    row.append(cell(logger.name, 'mono'), cell(logger.configuredLevel), cell(logger.effectiveLevel))
    row.tabIndex = 0
    const choose = () => {
      $('logger-name').value = logger.name
      $('logger-name').focus()
    }
    row.addEventListener('click', choose)
    row.addEventListener('keydown', event => {
      if (event.key !== 'Enter' && event.key !== ' ') return
      event.preventDefault()
      choose()
    })
    return row
  }))
  $('logger-names').replaceChildren(...loggers.map(logger => {
    const option = node('option')
    option.value = logger.name
    return option
  }))
  $('loggers-summary').hidden = false
  $('loggers-summary').textContent = result.loggers.total > loggers.length
    ? t('共 {total} 个 Logger，显示前 {shown} 个；输入关键字缩小范围', { total: result.loggers.total, shown: loggers.length })
    : t('共 {total} 个 Logger', { total: result.loggers.total })
}

function renderOverrides() {
  $('overrides-empty').hidden = overrides.length > 0
  $('override-rows').replaceChildren(...overrides.map(override => {
    const row = node('tr')
    const remaining = cell('', 'tools-remaining')
    remaining.dataset.expiresAt = override.expiresAt
    const action = node('td')
    const revert = node('button', 'capture-stop operate', t('立即恢复'))
    revert.type = 'button'
    revert.disabled = !status.canOperate
    revert.addEventListener('click', () => guarded(revert, async () => {
      await call(`loggers/overrides/revert?logger=${encodeURIComponent(override.logger)}`, { method: 'POST', operate: true },
        { 404: t('该临时级别已经恢复') })
      showNotice(t('已恢复 {logger} 的日志级别', { logger: override.logger }))
      await loadLoggers()
    }))
    action.append(revert)
    row.append(cell(override.logger, 'mono'), cell(override.level), cell(override.previousLevel || t('继承')),
      remaining, cell(override.operator), action)
    return row
  }))
  tick()
  clearInterval(countdown)
  countdown = overrides.length ? setInterval(tick, 1000) : null
}

function tick() {
  let expired = false
  document.querySelectorAll('.tools-remaining').forEach(element => {
    const seconds = Math.max(0, Math.round((Date.parse(element.dataset.expiresAt) - Date.now()) / 1000))
    element.textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
    if (seconds === 0) expired = true
  })
  if (expired) {
    clearInterval(countdown)
    countdown = null
    setTimeout(() => loadLoggers().catch(error => showNotice(error.message, true)), 1500)
  }
}

function setLevel() {
  const logger = $('logger-name').value.trim()
  if (!logger) return showNotice(t('请输入 Logger 名称'), true)
  guarded($('logger-set'), async () => {
    const result = await call('loggers/overrides', {
      method: 'POST', operate: true,
      json: { logger, level: $('logger-level').value, durationSeconds: Number($('logger-duration').value) }
    }, { 400: t('Logger 名称或级别无效'), 409: t('临时级别数量已达上限，请先恢复一些') })
    showNotice(t('{logger} 已临时设为 {level}，{time} 自动恢复', { logger: result.logger, level: result.level, time: time(result.expiresAt) }))
    await loadLoggers()
  })
}

// ---- CPU profiler ----

function startProfile() {
  guarded($('profile-start'), async () => {
    const session = await call('profiler', {
      method: 'POST',
      json: {
        durationSeconds: Number($('profile-duration').value), intervalMs: Number($('profile-interval').value),
        excludeIdle: $('profile-exclude-idle').checked, includeBlocked: $('profile-include-blocked').checked,
        groupByThread: $('profile-by-thread').checked
      },
      operate: true
    }, { 429: t('已有一个采样正在进行，请等待其结束') })
    flameFocus = null
    flameCursor = null
    await loadProfile(session.id)
  })
}

async function loadProfile(id) {
  clearTimeout(profileTimer)
  try {
    profile = await call(`profiler/${encodeURIComponent(id)}`)
  } catch (error) {
    showNotice(error.message, true)
    return
  }
  renderProfile()
  if (profile.session.state === 'RUNNING') profileTimer = setTimeout(() => loadProfile(id), 1000)
}

const PROFILE_STATES = { RUNNING: '采样中', FINISHED: '已完成', STOPPED: '已停止', FAILED: '失败' }

function renderProfile() {
  const session = profile.session
  const running = session.state === 'RUNNING'
  $('profile-start').disabled = locked($('profile-start'))
  $('profile-stop').hidden = !running
  const facts = $('profile-facts')
  facts.hidden = false
  facts.replaceChildren()
  fact(facts, t('状态'), t(PROFILE_STATES[session.state] || session.state))
  fact(facts, t('已采样'), `${(session.elapsedMs / 1000).toFixed(1)} / ${session.durationMs / 1000} s`)
  fact(facts, t('采样轮次'), session.ticks)
  fact(facts, t('线程栈样本'), session.samples)
  if (session.excludeIdle) fact(facts, t('排除的空闲样本'), session.idleSamples)
  fact(facts, t('采样开销'), `${session.overheadMs} ms`)
  fact(facts, t('节点'), profile.nodes)
  if (profile.truncatedSamples) fact(facts, t('超出节点上限的样本'), profile.truncatedSamples)
  fact(facts, t('操作人'), session.operator)
  if (session.error) fact(facts, t('错误'), session.error)
  const sampled = profile.root.total > 0
  $('flame-wrap').hidden = !sampled
  $('hot-methods').hidden = !sampled
  $('profile-empty').hidden = sampled
  if (!sampled) {
    $('profile-empty').textContent = running ? t('采样中，稍后显示火焰图…') : t('没有采到符合条件的线程栈样本')
    return
  }
  $('hot-rows').replaceChildren(...profile.hotMethods.map(method => {
    const row = node('tr')
    row.append(cell(method.method, 'mono'), cell(method.samples, 'numeric'), cell(`${method.percent}%`, 'numeric'))
    return row
  }))
  if (flameFocus && !findPath(profile.root, flameFocus)) flameFocus = null
  if (tab === 'profiler') drawFlame()
}

function stopProfile() {
  if (!profile) return
  guarded($('profile-stop'), async () => {
    await call(`profiler/${encodeURIComponent(profile.session.id)}/stop`, { method: 'POST', operate: true }, { 404: t('采样已经结束') })
    await loadProfile(profile.session.id)
  })
}

function downloadCollapsed() {
  if (!profile) return
  guarded($('profile-download'), async () => {
    const response = await call(`profiler/${encodeURIComponent(profile.session.id)}/collapsed`, { raw: true })
    await download(response, 'cpu-profile.collapsed.txt')
  })
}

/** Names from the root to the frame identified by {@code path}, or null when it no longer exists. */
function findPath(root, path) {
  let frame = root
  const frames = [root]
  for (const name of path.slice(1)) {
    frame = frame.children.find(child => child.name === name)
    if (!frame) return null
    frames.push(frame)
  }
  return frames
}

function color(name, highlighted) {
  if (highlighted) return '#b388ff'
  if (name.startsWith('[')) return '#c9d3e6'
  let hash = 0
  for (let index = 0; index < name.length; index++) hash = (hash * 31 + name.charCodeAt(index)) | 0
  const java = /^(java|javax|jdk|sun|com\.sun)\./.test(name)
  const hue = java ? 25 + (Math.abs(hash) % 20) : 5 + (Math.abs(hash) % 35)
  return `hsl(${hue}, ${java ? 70 : 85}%, ${java ? 72 : 62}%)`
}

function depthOf(frame) {
  let depth = 0
  for (const child of frame.children) depth = Math.max(depth, depthOf(child))
  return depth + 1
}

function drawFlame() {
  const canvas = $('flame')
  const path = flameFocus ? findPath(profile.root, flameFocus) : [profile.root]
  const focus = path[path.length - 1]
  const width = canvas.parentElement.clientWidth
  const rows = path.length - 1 + depthOf(focus)
  const height = rows * ROW_HEIGHT
  const ratio = window.devicePixelRatio || 1
  canvas.width = width * ratio
  canvas.height = height * ratio
  canvas.style.height = `${height}px`
  const context = canvas.getContext('2d')
  context.scale(ratio, ratio)
  context.font = '11px ui-monospace, SFMono-Regular, Menlo, monospace'
  context.textBaseline = 'middle'
  const search = $('flame-search').value.trim().toLowerCase()
  flameRects = []
  const box = (frame, x, y, w, names, faded) => {
    const highlighted = search && frame.name.toLowerCase().includes(search)
    context.fillStyle = faded ? '#e8ecf3' : color(frame.name, highlighted)
    context.fillRect(x, y, Math.max(w - 1, 0.5), ROW_HEIGHT - 1)
    if (w > 30) {
      context.fillStyle = '#1d2a3b'
      const label = frame.name
      const fit = Math.floor((w - 6) / 6.6)
      context.fillText(label.length > fit ? label.slice(0, Math.max(fit - 1, 0)) + '…' : label, x + 3, y + ROW_HEIGHT / 2)
    }
    flameRects.push({ x, y, w, frame, names, faded })
  }
  // Ancestors of the zoomed frame span the full width.
  path.slice(0, -1).forEach((frame, depth) => box(frame, 0, depth * ROW_HEIGHT, width, path.slice(0, depth + 1).map(f => f.name), true))
  const scale = width / Math.max(focus.total, 1)
  const layout = (frame, x, depth, names) => {
    const w = frame.total * scale
    if (w < 0.5) return
    box(frame, x, depth * ROW_HEIGHT, w, names, false)
    let childX = x
    for (const child of frame.children) {
      layout(child, childX, depth + 1, [...names, child.name])
      childX += child.total * scale
    }
  }
  layout(focus, 0, path.length - 1, path.map(frame => frame.name))
  const selected = flameCursor && flameRects.find(rect => sameNames(rect.names, flameCursor))
  if (selected) {
    context.strokeStyle = '#1d2a3b'
    context.lineWidth = 2
    context.strokeRect(selected.x + 1, selected.y + 1, Math.max(selected.w - 3, 1), ROW_HEIGHT - 3)
  }
  $('flame-path').textContent = path.length > 1 ? path.slice(1).map(frame => frame.name.replace(/^.*\.(\w+\.\w+)$/, '$1')).join(' › ') : ''
  $('flame-reset').hidden = path.length === 1
}

function flameHit(event) {
  const bounds = $('flame').getBoundingClientRect()
  const x = event.clientX - bounds.left
  const y = event.clientY - bounds.top
  return flameRects.find(rect => x >= rect.x && x < rect.x + rect.w && y >= rect.y && y < rect.y + ROW_HEIGHT)
}

function sameNames(left, right) {
  return left.length === right.length && left.every((name, index) => name === right[index])
}

function flameHover(event) {
  const hit = flameHit(event)
  if (!hit) {
    $('flame-tip').hidden = true
    return
  }
  const wrap = $('flame').parentElement.getBoundingClientRect()
  showFlameTip(hit, event.clientX - wrap.left, event.clientY - wrap.top)
}

function showFlameTip(hit, x, y) {
  const tip = $('flame-tip')
  const total = profile.root.total || 1
  tip.replaceChildren(node('strong', null, hit.frame.name),
    node('span', null, t('样本 {samples}（{percent}%），自身 {self}', {
      samples: hit.frame.total, percent: (hit.frame.total * 100 / total).toFixed(2), self: hit.frame.self
    })))
  tip.hidden = false
  const width = $('flame').parentElement.clientWidth
  tip.style.left = `${Math.max(0, Math.min(x + 12, width - 320))}px`
  tip.style.top = `${y + 14}px`
}

const FLAME_KEYS = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Enter', ' ', 'Escape']

/** Arrow keys move between frames, Enter zooms into the chosen frame and Escape zooms back out. */
function flameKey(event) {
  if (!profile || !flameRects.length || !FLAME_KEYS.includes(event.key)) return
  if (event.key === 'Escape') {
    if (!flameFocus) return
    flameFocus = flameFocus.length > 2 ? flameFocus.slice(0, -1) : null
  } else {
    const current = flameCursor && flameRects.find(rect => sameNames(rect.names, flameCursor))
    let next = current
    if (!current) {
      next = flameRects.find(rect => !rect.faded) || flameRects[0]
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      const row = flameRects.filter(rect => rect.y === current.y).sort((a, b) => a.x - b.x)
      next = row[row.indexOf(current) + (event.key === 'ArrowLeft' ? -1 : 1)] || current
    } else if (event.key === 'ArrowDown') {
      next = flameRects.filter(rect => rect.y === current.y + ROW_HEIGHT
        && rect.x >= current.x && rect.x < current.x + current.w).sort((a, b) => a.x - b.x)[0] || current
    } else if (event.key === 'ArrowUp') {
      next = flameRects.find(rect => rect.y === current.y - ROW_HEIGHT
        && current.x >= rect.x && current.x < rect.x + rect.w) || current
    } else {
      flameFocus = current.names.length > 1 ? current.names : null
    }
    flameCursor = next.names
  }
  event.preventDefault()
  drawFlame()
  const selected = flameCursor && flameRects.find(rect => sameNames(rect.names, flameCursor))
  if (selected) showFlameTip(selected, selected.x, selected.y)
  else $('flame-tip').hidden = true
}

function flameClick(event) {
  const hit = flameHit(event)
  if (!hit) return
  flameFocus = hit.names.length > 1 ? hit.names : null
  drawFlame()
}

// ---- heap ----

function takeHistogram() {
  guarded($('histogram-take'), async () => {
    $('histogram-take').textContent = t('采集中…')
    try {
      const histogram = await call(`heap/histogram?limit=${$('histogram-limit').value}`, { method: 'POST', operate: true },
        { 429: t('刚刚采集过或正在采集，请稍后再试'), 501: t('当前 JVM 不支持类直方图') })
      renderHistogram(histogram)
      status.histogramBaseline = true
      $('histogram-baseline').textContent = t('已有上次快照，本次将与其对比')
    } finally {
      $('histogram-take').textContent = t('采集直方图')
    }
  })
}

function histogramRow(row) {
  const element = node('tr', row.added ? 'is-selected' : null)
  const grows = row.deltaBytes > 0
  element.append(cell(row.className + (row.added ? ' ' + t('（新增）') : ''), 'mono'),
    cell(row.instances.toLocaleString(MeerkatI18n.locale), 'numeric'),
    cell(signed(row.deltaInstances, value => value.toLocaleString(MeerkatI18n.locale)), 'numeric ' + (row.deltaInstances > 0 ? 'warn' : 'muted')),
    cell(formatBytes(row.bytes), 'numeric'),
    cell(signed(row.deltaBytes, formatBytes), 'numeric ' + (grows ? 'bad' : 'muted')))
  return element
}

function renderHistogram(histogram) {
  const facts = $('histogram-facts')
  facts.hidden = false
  facts.replaceChildren()
  fact(facts, t('采集时间'), time(histogram.capturedAt))
  fact(facts, t('存活对象'), histogram.totalInstances.toLocaleString(MeerkatI18n.locale))
  fact(facts, t('总大小'), formatBytes(histogram.totalBytes))
  fact(facts, t('类数量'), histogram.classCount)
  if (histogram.previousAt) {
    fact(facts, t('对比'), time(histogram.previousAt))
    fact(facts, t('大小变化'), signed(histogram.deltaBytes, formatBytes))
    fact(facts, t('实例变化'), signed(histogram.deltaInstances, value => value.toLocaleString(MeerkatI18n.locale)))
  }
  $('histogram-growth').hidden = !histogram.previousAt || histogram.growth.length === 0
  $('growth-rows').replaceChildren(...histogram.growth.map(histogramRow))
  $('histogram-table').hidden = false
  $('histogram-rows').replaceChildren(...histogram.rows.map(histogramRow))
}

const HEAP_DUMP_ERRORS = {
  429: t('已有一个堆 Dump 正在生成'), 507: t('服务器临时目录的磁盘空间不足'), 501: t('当前 JVM 不支持生成堆 Dump')
}

/**
 * Posts a same-origin form into a hidden frame so the browser streams the dump straight to disk. A download never
 * loads the frame; anything that does load it is an error page (or a login page), which is reported instead.
 */
function nativeHeapDump() {
  const frame = node('iframe')
  frame.name = `meerkat-heap-dump-${Date.now()}`
  frame.hidden = true
  const form = node('form')
  form.method = 'POST'
  form.action = new URL('heap/dump', API).href
  form.target = frame.name
  form.hidden = true
  const csrf = csrfToken()
  if (csrf) {
    const field = node('input')
    field.type = 'hidden'
    field.name = '_csrf'
    field.value = csrf
    form.append(field)
  }
  document.body.append(frame, form)
  frame.addEventListener('load', () => {
    let text = ''
    let redirected = false
    try {
      // The empty frame's own about:blank load, or a browser that reports a finished download as a load.
      if (frame.contentWindow.location.href === 'about:blank') return
      text = frame.contentDocument?.body?.textContent || ''
      redirected = frame.contentWindow.location.pathname !== new URL(form.action).pathname
    } catch { redirected = true }
    if (!text.trim() && !redirected) return
    const code = Number(/(?:"status"\s*:\s*|status=)(\d{3})/.exec(text)?.[1])
    $('heap-dump-state').textContent = ''
    showNotice(HEAP_DUMP_ERRORS[code] || (code === 401 || code === 403 ? OPERATE_DENIED
      : redirected ? SESSION_EXPIRED_MESSAGE : t('下载失败（HTTP {status}）', { status: code || '?' })), true)
    frame.remove()
    form.remove()
  })
  form.submit()
}

function heapDump() {
  if (!window.confirm(t('生成堆 Dump 会让应用完全停顿，文件可能很大且包含敏感数据。确定继续？'))) return
  guarded($('heap-dump-start'), async () => {
    // Heap dumps can be as large as the heap: stream straight to disk where the browser allows it, so the page does
    // not hold the whole file in memory. The picker needs the click's user activation, so ask before the request.
    let file = null
    if (typeof window.showSaveFilePicker === 'function') {
      try {
        file = await window.showSaveFilePicker({ suggestedName: `heap-${Date.now()}.hprof` })
      } catch (error) {
        if (error.name === 'AbortError') return
        // Not allowed here (for example a cross-origin iframe): fall back to a browser download.
      }
    }
    // A form cannot carry the embedding page's bearer token; only then is the dump buffered in memory.
    if (!file && !bearerToken()) {
      nativeHeapDump()
      $('heap-dump-state').textContent = t('正在生成堆 Dump，完成后浏览器会开始下载，进度请看浏览器的下载列表')
      return
    }
    $('heap-dump-state').textContent = t('正在生成和下载，请不要关闭页面…')
    try {
      const response = await call('heap/dump', { method: 'POST', raw: true, operate: true }, HEAP_DUMP_ERRORS)
      if (file) await response.body.pipeTo(await file.createWritable())
      else await download(response, 'heap.hprof')
      $('heap-dump-state').textContent = t('已下载，服务器上的临时文件已删除')
    } catch (error) {
      $('heap-dump-state').textContent = ''
      throw error
    }
  })
}

// ---- configuration ----

function lookupConfig(name = $('config-name').value.trim()) {
  if (!name) return showNotice(t('请输入配置项名称'), true)
  $('config-name').value = name
  guarded($('config-lookup'), async () => {
    const result = await call(`config?name=${encodeURIComponent(name)}`, { operate: true }, { 400: t('配置项名称无效') })
    const container = $('config-result')
    const facts = node('div', 'live-facts tools-facts')
    fact(facts, t('配置项'), result.name)
    if (result.error) fact(facts, t('无法解析'), result.error)
    else fact(facts, t('最终值'), result.value ?? t('（未定义）'))
    fact(facts, t('定义它的配置源'), result.definitions.length)
    const rows = result.definitions.map((definition, index) => {
      const row = node('tr', definition.winner ? 'tools-winner' : null)
      row.append(cell(index + 1, 'numeric'), cell(definition.source, 'mono'), cell(definition.value, 'mono tools-value'),
        cell(definition.origin, 'mono'), cell(definition.winner ? t('生效') : t('被覆盖')))
      return row
    })
    container.hidden = false
    container.replaceChildren(facts, rows.length
      ? table([{ label: '#', numeric: true }, t('配置源（优先级从高到低）'), t('原始值'), t('位置'), t('状态')], rows)
      : node('p', 'empty', t('没有配置源定义这个配置项')))
  })
}

function searchConfig() {
  const query = $('config-name').value.trim()
  guarded($('config-search'), async () => {
    const result = await call(`config/search?q=${encodeURIComponent(query)}`, { operate: true })
    const rows = result.matches.map(match => {
      const row = node('tr')
      const name = node('td', 'mono')
      const link = node('a', null, match.name)
      link.href = '#config'
      link.addEventListener('click', event => {
        event.preventDefault()
        lookupConfig(match.name)
      })
      name.append(link)
      row.append(name, cell(match.error ? t('无法解析：{error}', { error: match.error }) : match.value, 'mono tools-value'),
        cell(match.source, 'mono'), cell(match.definitions, 'numeric'))
      return row
    })
    const summary = node('p', 'tools-note', result.truncated
      ? t('匹配 {total} 项，只显示前 {shown} 项', { total: result.total, shown: rows.length })
      : t('匹配 {total} 项', { total: result.total }))
    $('config-result').hidden = false
    $('config-result').replaceChildren(summary, rows.length
      ? table([t('配置项'), t('最终值'), t('生效的配置源'), { label: t('定义次数'), numeric: true }], rows)
      : node('p', 'empty', t('没有匹配的配置项')))
  })
}

// ---- classes ----

function lookupClass() {
  const name = $('class-name').value.trim()
  if (!name) return showNotice(t('请输入类名'), true)
  guarded($('class-lookup'), async () => {
    const result = await call(`classes?name=${encodeURIComponent(name)}`, {}, { 400: t('类名格式无效') })
    const facts = node('div', 'live-facts tools-facts')
    fact(facts, t('类'), result.className)
    fact(facts, t('已加载'), result.loaded ? t('是') : t('否'))
    if (result.loadedFrom) fact(facts, t('实际来源'), result.loadedFrom)
    if (result.module) fact(facts, t('模块'), result.module)
    if (result.classLoader) fact(facts, t('类加载器'), result.classLoader)
    if (result.error) fact(facts, t('错误'), result.error)
    const parts = [facts]
    if (result.duplicate) {
      parts.push(node('div', 'notice warning tools-warning',
        t('类路径上有 {count} 份副本，实际使用的是类加载器最先找到的那一份；版本不一致时可能出现 NoSuchMethodError 等问题。', { count: result.copies.length })))
    }
    const rows = result.copies.map(copy => {
      const row = node('tr', copy.loaded ? 'tools-winner' : null)
      row.append(cell(copy.location, 'mono'), cell(copy.first ? t('是') : ''), cell(copy.loaded ? t('是') : ''))
      return row
    })
    parts.push(rows.length ? table([t('位置'), t('最先找到'), t('实际加载')], rows)
      : node('p', 'empty', t('类路径上没有找到这个类文件')))
    parts.push(node('h3', 'tools-subheading', t('类加载器链')),
      node('pre', 'live-stack', result.classLoaderChain.join('\n  ↑ ')))
    $('class-result').hidden = false
    $('class-result').replaceChildren(...parts)
  })
}

function scanClasses() {
  guarded($('class-scan'), async () => {
    $('class-scan').textContent = t('扫描中…')
    try {
      const scan = await call('classes/duplicates', { method: 'POST', operate: true }, { 429: t('已有一个扫描正在进行') })
      renderScan(scan)
    } finally {
      $('class-scan').textContent = t('扫描重复类')
    }
  })
}

const STOP_REASONS = { 'duplicate limit': '重复类数量达到上限', 'class limit': '类数量达到上限', 'time limit': '扫描时间达到上限' }

function renderScan(scan) {
  const facts = node('div', 'live-facts tools-facts')
  fact(facts, t('扫描的 Jar 和目录'), `${scan.scannedSources} / ${scan.sources}`)
  fact(facts, t('类'), scan.classes.toLocaleString(MeerkatI18n.locale))
  fact(facts, t('重复类'), scan.duplicates.length)
  fact(facts, t('耗时'), `${scan.durationMs} ms`)
  if (scan.stopReason) fact(facts, t('提前结束'), t(STOP_REASONS[scan.stopReason] || scan.stopReason))
  const parts = [facts]
  if (!scan.duplicates.length) parts.push(node('p', 'empty', t('没有发现重复的类')))
  if (scan.pairs.length) {
    parts.push(node('h3', 'tools-subheading', t('包含相同类的 Jar')))
    parts.push(table([t('位置'), t('位置'), { label: t('相同的类'), numeric: true }, t('示例')], scan.pairs.map(pair => {
      const row = node('tr')
      row.append(cell(pair.first, 'mono'), cell(pair.second, 'mono'), cell(pair.classes, 'numeric'), cell(pair.samples.join(', '), 'mono'))
      return row
    })))
  }
  if (scan.duplicates.length) {
    const details = node('details', 'tools-details')
    details.append(node('summary', null, t('重复的类（{count}）', { count: scan.duplicates.length })))
    details.append(table([t('类'), t('位置')], scan.duplicates.map(duplicate => {
      const row = node('tr')
      const name = node('td', 'mono')
      const link = node('a', null, duplicate.className)
      link.href = '#classes'
      link.addEventListener('click', event => {
        event.preventDefault()
        $('class-name').value = duplicate.className
        lookupClass()
      })
      name.append(link)
      row.append(name, cell(duplicate.locations.join('\n'), 'mono tools-value'))
      return row
    })))
    parts.push(details)
  }
  if (scan.unreadable.length) {
    const details = node('details', 'tools-details')
    details.append(node('summary', null, t('无法读取的位置（{count}）', { count: scan.unreadable.length })))
    details.append(table([t('位置'), t('错误')], scan.unreadable.map(item => {
      const row = node('tr')
      row.append(cell(item.location, 'mono'), cell(item.error, 'mono'))
      return row
    })))
    parts.push(details)
  }
  $('class-result').hidden = false
  $('class-result').replaceChildren(...parts)
}

// ---- page ----

function selectTab(next) {
  tab = TABS.includes(next) ? next : TABS[0]
  for (const name of TABS) {
    const active = name === tab
    $(`tab-${name}`).classList.toggle('active', active)
    if (active) $(`tab-${name}`).setAttribute('aria-current', 'page')
    else $(`tab-${name}`).removeAttribute('aria-current')
    $(`${name}-panel`).hidden = !active
  }
  // On narrow screens the tab strip scrolls; keep a deep-linked tab visible.
  // Deferred until styles and translated labels are laid out, or the scroll position is lost on page load.
  requestAnimationFrame(() => $(`tab-${tab}`).scrollIntoView({ block: 'nearest', inline: 'nearest' }))
  // The canvas has no width while its panel is hidden, so redraw once the panel is shown.
  if (tab === 'profiler' && profile && !$('flame-wrap').hidden) drawFlame()
}

function searchLoggers() {
  clearTimeout(loggerSearchTimer)
  if (status?.enabled) loadLoggers().catch(error => showNotice(error.message, true))
}

function onEnter(id, action) {
  $(id).addEventListener('keydown', event => {
    if (event.key === 'Enter') {
      event.preventDefault()
      action()
    }
  })
}

$('refresh').addEventListener('click', () => refresh())
$('logger-set').addEventListener('click', setLevel)
$('logger-query').addEventListener('input', () => {
  clearTimeout(loggerSearchTimer)
  loggerSearchTimer = setTimeout(searchLoggers, 300)
})
onEnter('logger-query', searchLoggers)
$('profile-start').addEventListener('click', startProfile)
$('profile-stop').addEventListener('click', stopProfile)
$('profile-download').addEventListener('click', downloadCollapsed)
$('flame-reset').addEventListener('click', () => {
  flameFocus = null
  drawFlame()
})
$('flame-search').addEventListener('input', () => profile && drawFlame())
$('flame').addEventListener('mousemove', flameHover)
$('flame').addEventListener('mouseleave', () => { $('flame-tip').hidden = true })
$('flame').addEventListener('click', flameClick)
$('flame').addEventListener('keydown', flameKey)
$('flame').addEventListener('blur', () => { $('flame-tip').hidden = true })
window.addEventListener('resize', () => tab === 'profiler' && profile && !$('flame-wrap').hidden && drawFlame())
$('histogram-take').addEventListener('click', takeHistogram)
$('heap-dump-start').addEventListener('click', heapDump)
$('config-lookup').addEventListener('click', () => lookupConfig())
$('config-search').addEventListener('click', searchConfig)
onEnter('config-name', () => lookupConfig())
$('class-lookup').addEventListener('click', lookupClass)
$('class-scan').addEventListener('click', scanClasses)
onEnter('class-name', lookupClass)
window.addEventListener('hashchange', () => selectTab(location.hash.slice(1)))
selectTab(location.hash.slice(1))
initializeEmbeddedAuth().then(refresh)
