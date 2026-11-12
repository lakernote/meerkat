import { $, initializeEmbeddedAuth, kindName, node, poll, request as apiRequest } from './common.js?v=20261111.4'

const PAGE_SIZE = 50
let traces = { items: [], nextCursor: null, scanLimited: false, total: 0 }
// Cursor that starts each visited page; the first page always starts at the newest trace.
let pageCursors = ['']
let page = 0
let loadRequest = 0
let searchTimer
let detailRequest = 0
let resultFilter = 'all'

const sourceNames = { HTTP: 'HTTP', JOB: t('定时任务'), KAFKA_CONSUMER: t('Kafka 消费') }
const escText = value => value == null || value === '' ? '—' : String(value)

const request = (path, options = {}, deniedMessage = t('没有查看 Trace 的权限，请登录或检查 ObservabilityAccessPolicy')) =>
  apiRequest(path, options, deniedMessage)

const pad = value => String(value).padStart(2, '0')

// Today's traces show only the time, older ones add the date; the title keeps the full timestamp.
function compactTime(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return escText(value)
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  return date.toDateString() === new Date().toDateString() ? clock : `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${clock}`
}

function relativeTime(value) {
  const seconds = (Date.now() - new Date(value).getTime()) / 1000
  if (!Number.isFinite(seconds)) return ''
  if (seconds < 10) return t('刚刚')
  if (seconds < 60) return t('{n} 秒前', { n: Math.floor(seconds) })
  if (seconds < 3600) return t('{n} 分钟前', { n: Math.floor(seconds / 60) })
  if (seconds < 86400) return t('{n} 小时前', { n: Math.floor(seconds / 3600) })
  return t('{n} 天前', { n: Math.floor(seconds / 86400) })
}

function fullTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? escText(value) : date.toLocaleString(MeerkatI18n.locale)
}

const formatMs = value => `${Number(value || 0).toLocaleString()} ms`
// Durations are whole milliseconds, so 0 means the call finished within the first millisecond.
const formatDuration = value => Number(value) >= 1 ? formatMs(value) : '<1 ms'

function formatCpu(value) {
  const cpu = value == null || value === '' ? NaN : Number(value)
  return Number.isFinite(cpu) && cpu >= 0 ? formatDuration(cpu) : t('未采集')
}

function threadInfo(item, entryThread) {
  const sameThread = String(item.threadId) === String(entryThread.threadId)
  const name = item.threadName || t('线程 {id}', { id: item.threadId })
  return {
    label: sameThread ? t('入口线程') : t('子线程 · {name}', { name }),
    id: item.threadId,
    isEntry: Boolean(sameThread)
  }
}

function sourceChip(sourceType) {
  return node('span', `source-chip source-${String(sourceType || 'other').toLowerCase()}`, sourceNames[sourceType] || 'Trace')
}

function render() {
  const rows = traces.items
  const tbody = $('trace-rows')
  tbody.replaceChildren()
  for (const trace of rows) {
    const tr = document.createElement('tr')
    const timeCell = node('td', 'time')
    timeCell.append(node('span', '', compactTime(trace.finishedAt)), node('small', '', relativeTime(trace.finishedAt)))
    timeCell.title = fullTime(trace.finishedAt)
    const entryCell = node('td', 'entry')
    const entryLine = node('div', 'entry-line')
    entryLine.append(sourceChip(trace.sourceType), node('span', 'entry-name', trace.name || trace.tag || t('入口')))
    entryCell.append(entryLine)
    if (trace.error && (trace.errorType || trace.errorMessage)) {
      const reason = [trace.errorType, trace.errorMessage].filter(Boolean).join(': ')
      const errorLine = node('div', 'entry-error', reason)
      errorLine.title = reason
      entryCell.append(errorLine)
    }
    const resultCell = node('td', 'result')
    if (trace.error) resultCell.append(node('span', 'badge error', t('错误')))
    if (trace.slow) resultCell.append(node('span', 'badge slow', t('慢')))
    if (trace.highCpu) resultCell.append(node('span', 'badge resource', t('CPU 高')))
    if (trace.highAllocation) resultCell.append(node('span', 'badge resource', t('分配高')))
    if (trace.unfinishedSpanCount) {
      const unfinishedBadge = node('span', 'badge unfinished', t('子任务未完成'))
      unfinishedBadge.title = t('{n} 个子调用在入口请求结束时仍在运行', { n: trace.unfinishedSpanCount })
      resultCell.append(unfinishedBadge)
    }
    tr.append(timeCell, entryCell, resultCell)
    tr.append(node('td', `numeric duration${trace.slow ? ' warn' : ''}`, formatDuration(trace.durationMs)))
    tr.append(node('td', `numeric${trace.highCpu ? ' alert' : ''}`, formatCpu(trace.cpuTimeMs)))
    tr.append(node('td', `numeric allocated${trace.highAllocation ? ' alert' : ''}`, formatAllocatedBytes(trace.allocatedBytes)))
    tr.append(node('td', 'trace-id', trace.traceId))
    tr.tabIndex = 0
    tr.setAttribute('role', 'button')
    tr.setAttribute('aria-label', t('查看 {name} 的诊断 Trace 详情', { name: trace.name || trace.traceId }))
    tr.addEventListener('click', () => openTrace(trace))
    tr.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openTrace(trace) } })
    tbody.append(tr)
  }
  const searching = Boolean($('search').value.trim())
  $('empty').hidden = rows.length > 0
  $('result-count').textContent = traces.total >= 0 ? t('{n} 条诊断 Trace', { n: traces.total.toLocaleString() })
    : t('本页 {n} 条匹配', { n: rows.length })
  const noFilters = resultFilter === 'all' && !$('source-filter').value && !searching
  $('empty').textContent = traces.scanLimited
    ? t('已搜索的范围内没有匹配的 Trace，点击“下一页”继续搜索更早的记录')
    : t(noFilters ? '暂无诊断 Trace；正常请求不会显示在此列表中' : '没有符合当前筛选条件的 Trace')
  const pages = traces.total >= 0 ? Math.max(1, Math.ceil(traces.total / PAGE_SIZE)) : 0
  $('page-label').textContent = pages ? t('第 {page} / {pages} 页', { page: page + 1, pages: Math.max(pages, page + 1) })
    : t('第 {page} 页', { page: page + 1 })
  $('previous').disabled = page <= 0
  $('next').disabled = !traces.nextCursor
  $('next').textContent = traces.scanLimited ? t('继续搜索') : t('下一页')
}

function addFact(container, label, value, className = '', title = '') {
  const item = node('div', `fact ${className}`)
  const content = node('span', '', escText(value))
  if (title) content.title = title
  item.append(node('small', '', label), content)
  container.append(item)
}

function formatAllocatedBytes(value) {
  if (value == null || value === '') return t('未采集')
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return t('未采集')
  if (bytes < 1024) return `${bytes.toLocaleString()} B`
  const units = ['KiB', 'MiB', 'GiB']
  let amount = bytes
  let unit = 'B'
  for (let index = 0; index < units.length; index += 1) {
    amount /= 1024
    unit = units[index]
    if (amount < 1024 || index === units.length - 1) break
  }
  return `${amount.toFixed(1)} ${unit}`
}

// Same rule as the slow-trace log: consecutive finished leaf siblings with the same kind, thread, name and tag.
const canFold = item => !item.error && !item.slow && !item.unfinishedAtTraceEnd
  && !(Array.isArray(item.children) && item.children.length)
const sameFold = (first, other) => canFold(other) && first.kind === other.kind
  && String(first.threadId) === String(other.threadId) && first.name === other.name && (first.tag || '') === (other.tag || '')
// Repeats at or above this count are highlighted as a possible N+1 loop.
const REPEAT_WARNING = 10

function foldRepeats(children) {
  const folded = []
  for (let index = 0; index < children.length;) {
    const first = children[index]
    let end = index + 1
    if (canFold(first)) while (end < children.length && sameFold(first, children[end])) end += 1
    if (end - index > 1) {
      const spans = children.slice(index, end)
      const durations = spans.map(span => Number(span.durationMs) || 0)
      const starts = spans.map(span => Number(span.startOffsetMs) || 0)
      const totalMs = durations.reduce((sum, value) => sum + value, 0)
      folded.push({
        ...first,
        startOffsetMs: Math.min(...starts),
        durationMs: Math.max(...spans.map((span, at) => starts[at] + durations[at])) - Math.min(...starts),
        children: [],
        repeat: { count: spans.length, totalMs, minMs: Math.min(...durations), maxMs: Math.max(...durations) }
      })
    } else folded.push(first)
    index = end
  }
  return folded
}

// Spring transaction and @TraceSpan names are often fully qualified; the class and method are enough in a row.
function shortName(name) {
  const value = String(name || '')
  return /^([a-z_$][\w$]*\.){2,}[A-Z]/.test(value) ? value.split('.').slice(-2).join('.') : value
}

function inlineTag(item) {
  const tag = String(item.tag || '').trim()
  return !tag || String(item.name || '').includes(tag) ? '' : tag
}

const spanCost = item => item.repeat ? item.repeat.totalMs : Number(item.durationMs || 0)

function renderTraceTree(container, detailContainer, structuredNodes, totalMs) {
  const roots = Array.isArray(structuredNodes) ? structuredNodes : []
  container.replaceChildren()
  detailContainer.replaceChildren(node('p', '', t('选择一个调用节点查看详情')))
  if (!roots.length) {
    container.append(node('p', 'tree-empty', t('没有可展示的调用节点')))
    return
  }
  const flat = []
  const visit = (item, depth = 0) => {
    flat.push({ item, depth, threadKey: String(item.threadId) })
    for (const child of foldRepeats(Array.isArray(item.children) ? item.children : [])) visit(child, depth + 1)
  }
  roots.forEach(root => visit(root))
  let maxMs = Math.max(1, totalMs)
  for (const { item } of flat) {
    maxMs = Math.max(maxMs, Number(item.startOffsetMs) + Number(item.durationMs))
  }
  const children = flat.filter(({ depth }) => depth > 0)
  const entryThread = flat.find(({ depth }) => depth === 0)?.item
  const threadEntries = []
  const threadsByKey = new Map()
  for (const span of flat) {
    let thread = threadsByKey.get(span.threadKey)
    if (!thread) {
      thread = { key: `T${threadEntries.length + 1}`, info: threadInfo(span.item, entryThread), representative: span.item }
      threadsByKey.set(span.threadKey, thread)
      threadEntries.push(thread)
    }
  }
  const deepest = candidates => candidates.reduce((best, candidate) => {
    if (!best || candidate.depth > best.depth) return candidate
    if (candidate.depth === best.depth && spanCost(candidate.item) > spanCost(best.item)) return candidate
    return best
  }, null)
  const primary = deepest(children.filter(({ item }) => item.error))
    || deepest(children.filter(({ item }) => item.slow))
    || deepest(children.filter(({ item }) => item.unfinishedAtTraceEnd))
    || children.reduce((longest, candidate) =>
      !longest || spanCost(candidate.item) > spanCost(longest.item) ? candidate : longest, null)
    || flat[0]
  const scale = node('div', 'timeline-scale')
  scale.append(node('span', 'timeline-scale-label', ''))
  scale.append(node('span', 'timeline-thread-heading', t('线程', null, 'one')))
  const ticks = node('span', 'timeline-ticks')
  ticks.append(node('span', '', '0 ms'), node('span', '', `${Number((maxMs / 2).toFixed(maxMs < 10 ? 1 : 0))} ms`), node('span', '', `${maxMs} ms`))
  scale.append(ticks, node('span', 'timeline-scale-duration', ''))
  container.append(scale)
  if (threadEntries.length > 1) {
    const legend = node('div', 'thread-legend')
    legend.append(node('span', 'thread-legend-title', t('线程编号')))
    for (const thread of threadEntries) {
      const threadName = thread.representative.threadName || t('线程 {id}', { id: thread.representative.threadId })
      const role = thread.info.isEntry ? t('入口线程') : t('子线程')
      const threadClass = thread.info.isEntry ? ' entry-thread' : ' worker-thread'
      const item = node('span', `thread-legend-item${threadClass}`, `${thread.key} · ${role} · ${threadName}`)
      item.title = `${item.textContent} · ID ${thread.representative.threadId}`
      legend.append(item)
    }
    container.insertBefore(legend, scale)
  }
  let selectedRow = null
  let primaryRow = null
  for (const { item, depth, threadKey } of flat) {
    const thread = threadsByKey.get(threadKey)
    const threadClass = thread.info.isEntry ? ' entry-thread' : ' worker-thread'
    const threadLabel = `${thread.key} ${thread.info.label}`
    const repeat = item.repeat
    const row = node('button', `timeline-row${item.error ? ' has-error' : item.slow ? ' is-slow' : item.unfinishedAtTraceEnd ? ' is-unfinished' : ''}`)
    row.type = 'button'
    row.style.setProperty('--depth', Math.min(depth, 8))
    row.setAttribute('aria-label', [`${kindName(item.kind)} ${item.name || t('未命名')}`,
      repeat ? t('重复 {count} 次，合计 {ms} 毫秒', { count: repeat.count, ms: spanCost(item) }) : t('{ms} 毫秒', { ms: spanCost(item) }),
      threadLabel, item.error ? t('错误') : '', item.slow ? t('慢调用') : '', item.unfinishedAtTraceEnd ? t('请求结束时仍在运行') : '']
      .filter(Boolean).join(t('，')))
    const label = node('span', 'timeline-label')
    label.append(node('small', `timeline-kind kind-${String(item.kind || 'other').toLowerCase()}`, kindName(item.kind)))
    const name = node('span', 'timeline-name', shortName(item.name) || t('未命名调用'))
    name.title = item.name || ''
    label.append(name)
    if (repeat) {
      const repeated = node('span', `tree-state repeat-state${repeat.count >= REPEAT_WARNING ? ' is-many' : ''}`, `×${repeat.count}`)
      repeated.title = t('连续重复 {count} 次，已合并显示', { count: repeat.count })
      label.append(repeated)
    }
    if (item.error) label.append(node('span', 'tree-state error-state', t('错误')))
    if (item.slow) label.append(node('span', 'tree-state slow-state', t('慢')))
    if (item.unfinishedAtTraceEnd) label.append(node('span', 'tree-state unfinished-state', t('请求结束时仍运行')))
    const tag = inlineTag(item)
    if (tag) {
      const tagText = node('span', 'timeline-tag', tag)
      tagText.title = tag
      label.append(tagText)
    }
    const threadMarker = node('span', `timeline-thread${threadClass}`, thread.key)
    threadMarker.title = `${thread.key} · ${thread.info.label}${thread.representative.threadName ? ` · ${thread.representative.threadName}` : ''} · ID ${thread.representative.threadId}`
    const track = node('span', 'timeline-track')
    const bar = node('i', `timeline-bar${item.error ? ' error-fill' : item.slow ? ' slow-fill' : ''}${item.unfinishedAtTraceEnd ? ' is-unfinished' : ''}${repeat ? ' is-repeated' : ''}`)
    const offset = Number(item.startOffsetMs)
    const duration = Math.max(1, Number(item.durationMs) || 0)
    bar.style.left = `${Math.min(99, offset / maxMs * 100)}%`
    bar.style.width = `${Math.max(0.7, Math.min(100 - offset / maxMs * 100, duration / maxMs * 100))}%`
    bar.title = repeat ? t('{offset} ms 起 {count} 次调用，跨度 {duration} ms', { offset, count: repeat.count, duration })
      : t('{offset} ms 开始 · {duration} ms 持续', { offset, duration })
    track.append(bar)
    const cost = node('span', 'timeline-duration', formatDuration(spanCost(item)))
    if (repeat) cost.title = t('重复调用的合计耗时')
    row.append(label, threadMarker, track, cost)
    row.addEventListener('click', () => {
      if (selectedRow) selectedRow.setAttribute('aria-pressed', 'false')
      selectedRow = row
      row.setAttribute('aria-pressed', 'true')
      renderSpanDetail(detailContainer, item, offset, entryThread)
    })
    row.setAttribute('aria-pressed', 'false')
    container.append(row)
    if (primary && item === primary.item) primaryRow = row
  }
  primaryRow?.click()
}

function renderSpanDetail(container, item, offset, entryThread) {
  container.replaceChildren()
  const heading = node('div', 'span-detail-head')
  heading.append(node('span', 'eyebrow', 'SPAN DETAIL'), node('h4', '', item.name || t('未命名调用')))
  container.append(heading)
  const facts = node('div', 'span-facts')
  const thread = threadInfo(item, entryThread)
  const repeat = item.repeat
  const timing = repeat
    ? [[t('次数'), t('{count} 次', { count: repeat.count })], [t('合计'), formatDuration(repeat.totalMs)],
      [t('平均'), `${(repeat.totalMs / repeat.count).toFixed(1)} ms`], [t('最短 / 最长'), `${repeat.minMs} / ${repeat.maxMs} ms`],
      [t('首次开始'), formatMs(offset)]]
    : [[t('耗时'), formatDuration(item.durationMs)], [t('开始偏移'), formatMs(offset)]]
  for (const [label, value] of [
    [t('类型'), kindName(item.kind)], ...timing,
    [t('线程', null, 'one'), `${thread.label} · ${thread.id}`],
    [t('状态'), item.unfinishedAtTraceEnd ? t('请求结束时仍在运行（时长按入口结束截取）')
      : item.error ? `${t('错误')}${item.errorType ? ` · ${item.errorType}` : ''}` : item.slow ? t('慢调用') : t('已完成')]
  ]) facts.append(node('div', 'span-fact', t('{label}：{value}', { label, value })))
  container.append(facts)
  if (repeat && repeat.count >= REPEAT_WARNING) {
    container.append(node('p', 'span-hint',
      t('同一调用在循环中连续执行了 {count} 次。如果是逐条查询，可以考虑改为一次批量查询（N+1 问题）。', { count: repeat.count })))
  }
  if (item.tag) {
    const tag = node('pre', 'span-tag', item.tag)
    container.append(tag)
  }
}

async function openTrace(summary) {
  const requestId = ++detailRequest
  $('detail-title').textContent = summary.name || summary.traceId || t('调用详情')
  $('detail-source').replaceWith(Object.assign(sourceChip(summary.sourceType), { id: 'detail-source' }))
  $('detail-trace-id').textContent = summary.traceId || ''
  $('copy-trace-id').textContent = t('复制')
  $('facts').replaceChildren()
  $('error-stack-details').open = false
  $('tree').replaceChildren(node('p', 'tree-empty', t('正在读取调用树…')))
  $('span-detail').replaceChildren()
  $('error-section').hidden = true
  $('detail-dialog').showModal()
  $('detail-title').focus()
  try {
    const detail = await request(`/traces/${encodeURIComponent(summary.traceId)}?source=${encodeURIComponent(summary.sourceType || '')}`,
      { notFoundMessage: t('这条 Trace 已不在保留范围内') })
    if (requestId !== detailRequest || !$('detail-dialog').open) return
    $('facts').replaceChildren()
    const resourceAlerts = [detail.highCpu ? t('CPU 时间超阈值') : '', detail.highAllocation ? t('内存分配量超阈值') : ''].filter(Boolean)
    const result = [detail.error ? t('错误') : '', detail.slow ? t('慢调用') : '', ...resourceAlerts]
      .filter(Boolean).join(' · ') || t('正常')
    addFact($('facts'), t('结果'), result, detail.error ? 'bad' : detail.slow || resourceAlerts.length ? 'warn' : '')
    addFact($('facts'), t('状态'), detail.status || '—')
    addFact($('facts'), t('总耗时'), formatDuration(detail.durationMs), detail.slow ? 'warn' : '')
    addFact($('facts'), t('CPU 时间'), formatCpu(detail.cpuTimeMs), detail.highCpu ? 'warn' : '',
      t('入口线程在本次入口执行期间实际占用 CPU 的累计时间；不是 CPU 使用率，也不包含子线程'))
    addFact($('facts'), t('内存分配量'), formatAllocatedBytes(detail.allocatedBytes), detail.highAllocation ? 'warn' : '',
      t('入口线程在本次入口执行期间累计分配的对象大小；不是当前堆占用，也不包含子线程或远程服务'))
    addFact($('facts'), t('入口线程'), detail.threadName || detail.threadId)
    addFact($('facts'), t('完成时间'), fullTime(detail.finishedAt))
    if (detail.error || detail.errorType || detail.errorStackTrace) {
      $('error-section').hidden = false
      $('error-message').textContent = [detail.errorType, detail.errorMessage].filter(Boolean).join(': ') || t('有错误标记，未捕获到异常堆栈。')
      $('error-stack').textContent = detail.errorStackTrace || t('当前异常没有可用的堆栈。')
    }
    renderTraceTree($('tree'), $('span-detail'), detail.treeNodes, Number(detail.durationMs || 0))
  } catch (error) {
    if (requestId !== detailRequest || !$('detail-dialog').open) return
    $('tree').replaceChildren(node('p', 'tree-empty', error.message))
  }
}

function traceQuery() {
  const params = new URLSearchParams({ kind: resultFilter, limit: String(PAGE_SIZE) })
  if ($('source-filter').value) params.set('source', $('source-filter').value)
  const query = $('search').value.trim()
  if (query) params.set('q', query)
  if (pageCursors[page]) params.set('before', pageCursors[page])
  return `/traces?${params}`
}

function showPage(target) {
  page = target
  pageCursors.length = page + 1
  return refresh()
}

async function refresh() {
  const requestId = ++loadRequest
  try {
    const result = await request(traceQuery())
    if (requestId !== loadRequest) return true
    traces = result
    $('notice').hidden = true
    render()
    return true
  } catch (error) {
    if (requestId !== loadRequest) return true
    $('notice').textContent = t('{message}。请检查应用权限和服务状态。', { message: error.message })
    $('notice').hidden = false
    return false
  }
}

// ?q=, ?source= and ?kind= let other pages link to a pre-filtered list, for example a trace ID copied from a log line.
function applyLinkedFilters() {
  const params = new URLSearchParams(window.location.search)
  const kind = params.get('kind')
  if (['slow', 'error', 'resource'].includes(kind)) selectResultFilter(kind)
  const query = params.get('q')
  if (query) $('search').value = query.slice(0, 200)
  const source = params.get('source')
  if (source && [...$('source-filter').options].some(option => option.value === source)) $('source-filter').value = source
}

function selectResultFilter(value) {
  resultFilter = value
  for (const button of $('result-filter').querySelectorAll('button')) {
    button.setAttribute('aria-pressed', String(button.dataset.value === value))
  }
}

function bind() {
  for (const button of $('result-filter').querySelectorAll('button')) {
    button.addEventListener('click', () => { selectResultFilter(button.dataset.value); showPage(0) })
  }
  $('source-filter').addEventListener('change', () => showPage(0))
  $('copy-trace-id').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText($('detail-trace-id').textContent)
      $('copy-trace-id').textContent = t('已复制')
    } catch { $('copy-trace-id').textContent = t('复制失败') }
  })
  $('search').addEventListener('input', () => {
    window.clearTimeout(searchTimer)
    searchTimer = window.setTimeout(() => showPage(0), 300)
  })
  // One page request at a time: a double click would otherwise request the same cursor twice and skip a page number.
  let paging = false
  const turnPage = async (target, cursor) => {
    if (paging) return
    paging = true
    const previous = page
    try {
      if (cursor) pageCursors[target] = cursor
      page = target
      pageCursors.length = page + 1
      if (!(await refresh())) {
        page = previous
        pageCursors.length = page + 1
      }
    } finally {
      paging = false
    }
  }
  $('previous').addEventListener('click', () => { if (page > 0) turnPage(page - 1) })
  $('next').addEventListener('click', () => { if (traces.nextCursor) turnPage(page + 1, traces.nextCursor) })
  $('refresh').addEventListener('click', refresh)
  $('close-dialog').addEventListener('click', () => { detailRequest += 1; $('detail-dialog').close() })
  $('detail-dialog').addEventListener('close', () => { detailRequest += 1 })
  $('clear-history').addEventListener('click', () => $('clear-dialog').showModal())
  $('cancel-clear').addEventListener('click', () => $('clear-dialog').close())
  $('confirm-clear').addEventListener('click', async () => {
    $('confirm-clear').disabled = true
    try {
      await request('/traces/clear', { method: 'POST' },
        t('没有清空历史的权限，请检查 ObservabilityAccessPolicy#canClear 或 CSRF 配置'))
      $('clear-dialog').close()
      await showPage(0)
    } catch (error) {
      $('notice').textContent = error.message
      $('notice').hidden = false
    } finally { $('confirm-clear').disabled = false }
  })
}

// ---- Error groups: failed traces grouped by error type and message ----
let errorWindow = '24h'
let errorRequest = 0

async function loadErrorGroups() {
  if (!$('error-groups').open) return
  const requestId = ++errorRequest
  const params = new URLSearchParams({ window: errorWindow, limit: '50' })
  if ($('source-filter').value) params.set('source', $('source-filter').value)
  try {
    const report = await request(`/errors?${params}`)
    if (requestId !== errorRequest) return
    renderErrorGroups(report)
  } catch (error) {
    if (requestId !== errorRequest) return
    $('error-group-rows').replaceChildren()
    $('error-groups-count').textContent = error.message
  }
}

function renderErrorGroups(report) {
  const rows = $('error-group-rows')
  rows.replaceChildren()
  $('error-groups-empty').hidden = report.groups.length > 0
  $('error-groups-count').textContent = report.failures
    ? t('{failures} 次失败，{groups} 类', { failures: report.failures, groups: report.total }) : ''
  for (const group of report.groups) {
    const row = node('tr')
    const what = node('td', 'error-group-what')
    const type = node('strong', '', group.errorType)
    what.append(type)
    if (group.newInWindow) what.append(node('span', 'badge error', t('新出现')))
    if (group.message) what.append(node('span', 'error-group-message', group.message))
    what.title = group.sampleMessage || ''
    const count = node('td', 'numeric', Number(group.count).toLocaleString())
    const entries = node('td', 'error-group-entries')
    for (const entry of group.entries) {
      entries.append(node('span', '', `${entry.name} ×${entry.count}`))
    }
    const last = node('td', '', relativeTime(group.lastSeen))
    last.title = t('首次 {first}\n最近 {last}', { first: fullTime(group.firstSeen), last: fullTime(group.lastSeen) })
    const actions = node('td', 'error-group-actions')
    const open = node('button', 'quiet-button', t('最近一条'))
    open.type = 'button'
    open.addEventListener('click', () => openTrace({ traceId: group.latestTraceId, sourceType: group.latestSourceType,
      name: group.errorType }))
    const filter = node('button', 'quiet-button', t('筛选'))
    filter.type = 'button'
    filter.addEventListener('click', () => {
      selectResultFilter('error')
      $('search').value = group.errorType.startsWith('HTTP_') || group.errorType === '—'
        ? (group.entries[0]?.name || '') : group.errorType
      showPage(0)
      $('search').scrollIntoView({ block: 'center' })
    })
    actions.append(open, filter)
    const trend = node('td')
    trend.append(sparkline(group.trend))
    row.append(what, count, trend, entries, last, actions)
    rows.append(row)
  }
}

function sparkline(trend) {
  const max = Math.max(1, ...trend)
  const chart = node('span', 'sparkline')
  chart.setAttribute('aria-hidden', 'true')
  for (const value of trend) {
    const bar = node('i')
    bar.style.height = value ? `${Math.max(12, Math.round(value / max * 100))}%` : '0'
    chart.append(bar)
  }
  return chart
}

function bindErrorGroups() {
  $('error-groups').addEventListener('toggle', loadErrorGroups)
  for (const button of $('error-window').querySelectorAll('button')) {
    button.addEventListener('click', () => {
      errorWindow = button.dataset.value
      for (const other of $('error-window').querySelectorAll('button')) {
        other.setAttribute('aria-pressed', String(other === button))
      }
      loadErrorGroups()
    })
  }
  $('source-filter').addEventListener('change', loadErrorGroups)
  $('refresh').addEventListener('click', loadErrorGroups)
}

async function loadPermissions() {
  try {
    const permissions = await request('/permissions')
    $('clear-history').hidden = !permissions.canClear
  } catch {
    $('clear-history').hidden = true
  }
}

applyLinkedFilters()
bind()
bindErrorGroups()
initializeEmbeddedAuth().then(() => Promise.all([loadPermissions(), refresh()]))
// Only the newest page is polled; older pages and text searches stay as loaded until the user acts.
poll(() => page === 0 && !$('search').value.trim() ? refresh() : true, 10000)
