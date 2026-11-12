import {
  $, categoryNames, countsSince, DISABLED_MESSAGE, formatValue, initializeEmbeddedAuth, lineChart, poll, request, stepLabel
} from './metrics-common.js?v=20261111.4'

const PERIODS = new Set(['1h', '24h', '7d', '30d'])
const REFRESH_MS = 60000
/*
 * Series keys are meter[:group][@stat] (see ResourceMeters). A series item names a meter and optionally a stat;
 * every group of that meter becomes one line. "plus" adds another meter of the same group (queue capacity) and
 * "per" divides by another stat of the same meter (timer average = total / count). Charts marked perStep show
 * amounts per point, so their title carries the step.
 */
const RESOURCE_CHARTS = [
  { group: 'cpu-memory', title: t('CPU 使用率'), unit: 'percent', hint: t('峰值来自每 10 秒的采样'), series: [
    { meter: 'process.cpu.usage', label: t('进程'), scale: 100 },
    { meter: 'process.cpu.usage', stat: 'max', label: t('进程峰值'), scale: 100 },
    { meter: 'system.cpu.usage', label: t('系统'), scale: 100 },
    { meter: 'system.cpu.usage', stat: 'max', label: t('系统峰值'), scale: 100 }] },
  { group: 'cpu-memory', title: t('系统负载（1 分钟）'), unit: 'count', hint: t('持续高于 CPU 核数说明 CPU 不够用'), series: [
    { meter: 'system.load.average.1m', label: t('负载') }, { meter: 'system.cpu.count', label: t('CPU 核数') }] },
  { group: 'cpu-memory', title: t('堆内存'), unit: 'bytes', series: [
    { meter: 'jvm.memory.used', group: 'heap', label: t('已用') },
    { meter: 'jvm.memory.committed', group: 'heap', label: t('已提交') },
    { meter: 'jvm.memory.max', group: 'heap', label: t('上限') }] },
  { group: 'cpu-memory', title: t('非堆内存'), unit: 'bytes', series: [
    { meter: 'jvm.memory.used', group: 'nonheap', label: t('已用') },
    { meter: 'jvm.memory.committed', group: 'nonheap', label: t('已提交') }] },
  { group: 'cpu-memory', title: t('直接内存与映射缓冲区'), unit: 'bytes', hint: t('Netty、NIO 等堆外内存'), series: [
    { meter: 'jvm.buffer.memory.used' }] },
  { group: 'gc', title: t('GC 次数'), unit: 'count', perStep: true, series: [{ meter: 'jvm.gc.pause', stat: 'count' }] },
  { group: 'gc', title: t('GC 停顿总时长'), unit: 'ms', perStep: true, series: [{ meter: 'jvm.gc.pause', stat: 'total' }] },
  { group: 'gc', title: t('GC 最长单次停顿'), unit: 'ms', hint: t('请求变慢时先看这里'), series: [
    { meter: 'jvm.gc.pause', stat: 'max' }] },
  { group: 'gc', title: t('GC 后存活数据'), unit: 'bytes', hint: t('GC 后仍持续上涨通常是内存泄漏'), series: [
    { meter: 'jvm.gc.live.data.size', label: t('存活数据') }, { meter: 'jvm.gc.max.data.size', label: t('老年代上限') }] },
  { group: 'gc', title: t('GC 压力'), unit: 'percent', hint: t('老年代接近满或停顿占比升高时容易 OOM'), series: [
    { meter: 'jvm.memory.usage.after.gc', label: t('GC 后老年代占用'), scale: 100 },
    { meter: 'jvm.gc.overhead', label: t('GC 停顿占比'), scale: 100 }] },
  { group: 'gc', title: t('内存分配与晋升'), unit: 'bytes', perStep: true, series: [
    { meter: 'jvm.gc.memory.allocated', label: t('分配') }, { meter: 'jvm.gc.memory.promoted', label: t('晋升到老年代') }] },
  { group: 'threads', title: t('JVM 线程'), unit: 'count', series: [
    { meter: 'jvm.threads.live', label: t('活跃') }, { meter: 'jvm.threads.daemon', label: t('守护') },
    { meter: 'jvm.threads.peak', label: t('历史峰值') }] },
  { group: 'threads', title: t('Web 容器线程'), unit: 'count', hint: t('忙碌峰值贴近上限说明请求线程耗尽'), series: [
    { meter: 'tomcat.threads.busy', label: t('忙碌') }, { meter: 'tomcat.threads.busy', stat: 'max', label: t('忙碌峰值') },
    { meter: 'tomcat.threads.current', label: t('当前') }, { meter: 'tomcat.threads.config.max', label: t('上限') },
    { meter: 'jetty.threads.busy', label: t('忙碌') }, { meter: 'jetty.threads.busy', stat: 'max', label: t('忙碌峰值') },
    { meter: 'jetty.threads.current', label: t('当前') }, { meter: 'jetty.threads.config.max', label: t('上限') }] },
  { group: 'threads', title: t('线程池线程'), unit: 'count', series: [
    { meter: 'executor.active', label: t('活跃') }, { meter: 'executor.active', stat: 'max', label: t('活跃峰值') },
    { meter: 'executor.pool.size', label: t('线程数', null, 'pool') }, { meter: 'executor.pool.core', label: t('核心') },
    { meter: 'executor.pool.max', label: t('上限') }] },
  { group: 'threads', title: t('线程池队列'), unit: 'count', hint: t('排队峰值接近容量时任务会被拒绝'), series: [
    { meter: 'executor.queued', label: t('排队') }, { meter: 'executor.queued', stat: 'max', label: t('排队峰值') },
    { meter: 'executor.queued', plus: 'executor.queue.remaining', label: t('容量') }] },
  { group: 'threads', title: t('线程池拒绝任务'), unit: 'count', perStep: true,
    hint: t('含 CallerRuns 等策略处理的任务，出现即说明线程池已满'), series: [
      { meter: 'meerkat.executor.rejected', label: t('拒绝') }] },
  { group: 'threads', title: t('线程池完成任务'), unit: 'count', perStep: true, series: [
    { meter: 'executor.completed', label: t('完成') }] },
  { group: 'database', title: t('数据库连接池'), unit: 'count', hint: t('出现等待说明连接不够用'), series: [
    { meter: 'hikaricp.connections.active', label: t('活跃') },
    { meter: 'hikaricp.connections.active', stat: 'max', label: t('活跃峰值') },
    { meter: 'hikaricp.connections.idle', label: t('空闲') },
    { meter: 'hikaricp.connections.pending', stat: 'max', label: t('等待峰值') },
    { meter: 'hikaricp.connections.max', label: t('上限') }] },
  { group: 'database', title: t('获取连接超时'), unit: 'count', perStep: true, series: [
    { meter: 'hikaricp.connections.timeout', label: t('超时') }] },
  { group: 'database', title: t('获取连接等待'), unit: 'ms', hint: t('调用方等连接的时间'), series: [
    { meter: 'hikaricp.connections.acquire', stat: 'total', per: 'count', label: t('平均') },
    { meter: 'hikaricp.connections.acquire', stat: 'max', label: t('最大') }] },
  { group: 'database', title: t('连接持有时间'), unit: 'ms', hint: t('偏长说明慢 SQL 或大事务占着连接'), series: [
    { meter: 'hikaricp.connections.usage', stat: 'total', per: 'count', label: t('平均') },
    { meter: 'hikaricp.connections.usage', stat: 'max', label: t('最大') }] },
  { group: 'web', title: t('HTTP 连接'), unit: 'count', series: [
    { meter: 'tomcat.connections.current', label: t('当前') },
    { meter: 'tomcat.connections.current', stat: 'max', label: t('峰值') },
    { meter: 'tomcat.connections.keepalive.current', label: t('长连接') },
    { meter: 'tomcat.connections.config.max', label: t('上限') },
    { meter: 'jetty.connections.current', label: t('当前') },
    { meter: 'jetty.connections.current', stat: 'max', label: t('峰值') }] },
  { group: 'web', title: t('网络流量'), unit: 'bytes', perStep: true, series: [
    { meter: 'tomcat.global.received', label: t('入站') }, { meter: 'tomcat.global.sent', label: t('出站') },
    { meter: 'jetty.connections.bytes.in', label: t('入站') }, { meter: 'jetty.connections.bytes.out', label: t('出站') }] },
  { group: 'web', title: t('HTTP 会话'), unit: 'count', series: [
    { meter: 'tomcat.sessions.active.current', label: t('活跃会话') }] },
  { group: 'system', title: t('磁盘空间'), unit: 'bytes', series: [
    { meter: 'disk.free', label: t('可用') }, { meter: 'disk.total', label: t('总量') }] },
  { group: 'system', title: t('文件句柄'), unit: 'count', series: [
    { meter: 'process.files.open', label: t('已打开') }, { meter: 'process.files.max', label: t('上限') }] },
  { group: 'system', title: t('已加载类'), unit: 'count', series: [{ meter: 'jvm.classes.loaded', label: t('类') }] },
  { group: 'system', title: t('运行时长'), unit: 'duration', hint: t('曲线回落说明实例重启过'), series: [
    { meter: 'process.uptime', label: t('运行时长') }] }
]
// Placed in the service overview right after the request volume.
const STATUS_CHART = { group: 'overview', title: t('HTTP 响应状态'), unit: 'count', perStep: true,
  hint: t('429 通常是限流，单独列出'), series: [{ meter: 'http.server.requests' }] }
const STATUS_ORDER = ['2xx', '3xx', '4xx', '429', '5xx', '1xx', 'unknown']
const DEPENDENCIES = ['SQL', 'REMOTE', 'REDIS', 'KAFKA_PRODUCER']
/** Sections of the page in display order; collapsed by default unless {@code open}. The choice is remembered per browser. */
const GROUPS = [
  { id: 'overview', title: t('服务概览'), note: t('HTTP 请求量、响应状态、错误率、耗时和依赖调用'), open: true },
  { id: 'cpu-memory', title: t('CPU 与内存'), note: t('CPU 使用率、系统负载、堆、非堆和直接内存'), open: true },
  { id: 'gc', title: t('GC'), note: t('GC 次数、停顿、存活数据和分配速率'), open: true },
  { id: 'threads', title: t('线程与线程池'), note: t('JVM 和 Web 容器线程、应用线程池') },
  { id: 'database', title: t('数据库连接池'), note: t('活跃、等待连接、获取等待、持有时间和超时') },
  { id: 'web', title: t('Web 连接与流量'), note: t('HTTP 连接、网络流量、HTTP 会话') },
  { id: 'system', title: t('系统'), note: t('磁盘空间、文件句柄、已加载类、运行时长') }
]
const OPEN_STATE_KEY = 'meerkat:metrics:open-groups'
let charts = []
let poller = null
let disabled = false
// Only the newest refresh renders; a slower one for a previous period is dropped.
let refreshRequest = 0
const sections = new Map()

function storedOpenGroups() {
  try {
    const stored = JSON.parse(window.localStorage.getItem(OPEN_STATE_KEY) || 'null')
    return Array.isArray(stored) ? new Set(stored) : null
  } catch { return null }
}

function rememberOpenGroups() {
  const open = [...sections].filter(([, section]) => section.details.open).map(([id]) => id)
  try { window.localStorage.setItem(OPEN_STATE_KEY, JSON.stringify(open)) } catch { /* storage unavailable: keep in memory only */ }
}

function buildSections() {
  const stored = storedOpenGroups()
  for (const group of GROUPS) {
    const details = document.createElement('details')
    details.className = 'metric-group'
    details.open = stored ? stored.has(group.id) : Boolean(group.open)
    const summary = document.createElement('summary')
    const title = document.createElement('span')
    title.className = 'metric-group-title'
    title.textContent = group.title
    const note = document.createElement('span')
    note.className = 'metric-group-note'
    note.textContent = group.note
    const count = document.createElement('span')
    count.className = 'metric-group-count'
    summary.append(title, note, count)
    const grid = document.createElement('div')
    grid.className = 'metric-grid'
    details.append(summary, grid)
    details.addEventListener('toggle', rememberOpenGroups)
    $('metric-groups').append(details)
    sections.set(group.id, { details, grid, count })
  }
}

function fillSection(id, list) {
  const section = sections.get(id)
  section.grid.replaceChildren(...list.map(chart => chart.card))
  section.count.textContent = t('{n} 个图表', { n: list.length })
  section.details.hidden = list.length === 0
}

function setAllOpen(open) {
  for (const section of sections.values()) section.details.open = open
  rememberOpenGroups()
}

function parseKey(key) {
  const at = key.lastIndexOf('@')
  const name = at < 0 ? key : key.slice(0, at)
  const colon = name.indexOf(':')
  return { meter: colon < 0 ? name : name.slice(0, colon), group: colon < 0 ? '' : name.slice(colon + 1),
    stat: at < 0 ? '' : key.slice(at + 1) }
}

/** Indexes series by meter@stat, then by group. */
function indexSeries(series) {
  const index = new Map()
  for (const [key, values] of Object.entries(series)) {
    const { meter, group, stat } = parseKey(key)
    const id = `${meter}@${stat}`
    if (!index.has(id)) index.set(id, new Map())
    index.get(id).set(group, values)
  }
  return index
}

function groupLabel(meter, group) {
  if (group === 'other') return t('其他')
  if (meter === 'http.server.requests') return group === 'unknown' ? t('未知') : group
  if (meter === 'jvm.gc.pause') {
    if (/minor/i.test(group)) return 'Young GC'
    if (/major/i.test(group)) return 'Old/Full GC'
  }
  return group.replace(/_+/g, ' ').trim()
}

function statusRank(group) {
  const rank = STATUS_ORDER.indexOf(group)
  return rank < 0 ? STATUS_ORDER.length : rank
}

function combine(values, other, operation) {
  return values.map((value, index) => value == null || other?.[index] == null ? null : operation(value, other[index]))
}

function chartTitle(chart, stepMillis) {
  return chart.perStep ? t('{title}（{step}）', { title: chart.title, step: stepLabel(stepMillis) }) : chart.title
}

function resourceCharts(resources, list = RESOURCE_CHARTS) {
  const index = indexSeries(resources.series)
  const result = []
  for (const chart of list) {
    const series = []
    for (const item of chart.series) {
      const groups = index.get(`${item.meter}@${item.stat || ''}`)
      if (!groups) continue
      const ordered = item.meter === 'http.server.requests'
        ? [...groups].sort(([a], [b]) => statusRank(a) - statusRank(b)) : groups
      for (const [group, raw] of ordered) {
        if (item.group != null && group !== item.group) continue
        let values = raw
        if (item.plus) values = combine(values, index.get(`${item.plus}@`)?.get(group), (a, b) => a + b)
        if (item.per) {
          values = combine(values, index.get(`${item.meter}@${item.per}`)?.get(group), (a, b) => b > 0 ? a / b : null)
        }
        const name = item.group == null && group ? groupLabel(item.meter, group) : ''
        series.push({
          label: [name, item.label].filter(Boolean).join(' ') || item.meter,
          values: values.map(value => value == null ? null : value * (item.scale || 1))
        })
      }
    }
    if (series.some(item => item.values.some(value => value != null))) {
      const rendered = lineChart({ ...chart, title: chartTitle(chart, resources.stepMillis),
        timestamps: resources.timestamps, stepMillis: resources.stepMillis, series })
      result.push({ ...rendered, group: chart.group })
    }
  }
  return result
}

/**
 * The service overview reuses the per-category call trends (the HTTP RED view plus dependency latency) and the
 * response status classes Spring Boot's http.server.requests already counts.
 */
function overviewCharts(trends, resources) {
  const status = resourceCharts(resources, [STATUS_CHART])
  const http = trends?.HTTP
  if (!http || !http.points.some(point => point.count > 0)) return status
  const timestamps = http.points.map(point => point.timestamp)
  const base = { timestamps, stepMillis: http.stepMillis }
  const step = stepLabel(http.stepMillis)
  const charts = [
    lineChart({ ...base, title: t('HTTP 请求量（{step}）', { step }), unit: 'count', series: [
      { label: t('请求'), values: countsSince(http, point => point.count) },
      { label: t('错误'), values: countsSince(http, point => point.errors) }] }),
    ...status,
    lineChart({ ...base, title: t('HTTP 错误率'), unit: 'percent', hint: t('异常或 5xx 响应'), series: [
      { label: t('错误率'), values: http.points.map(point => point.count > 0 ? point.errors * 100 / point.count : null) }] }),
    lineChart({ ...base, title: t('HTTP 耗时'), unit: 'ms', series: [
      { label: t('平均'), values: http.points.map(point => point.meanMs) },
      { label: 'P95', values: http.points.map(point => point.p95Ms) },
      { label: t('最大'), values: http.points.map(point => point.maxMs) }] })
  ]
  const dependencies = DEPENDENCIES.map(category => [category, trends[category]])
    .filter(([, trend]) => trend?.points.some(point => point.count > 0))
  if (dependencies.length) {
    charts.push(lineChart({ ...base, title: t('依赖调用平均耗时'), unit: 'ms', hint: t('和 HTTP 耗时对照，找出变慢的下游'),
      series: dependencies.map(([category, trend]) => ({ label: categoryNames[category],
        values: trend.points.map(point => point.meanMs) })) }))
    if (dependencies.some(([, trend]) => trend.points.some(point => point.errors > 0))) {
      charts.push(lineChart({ ...base, title: t('依赖调用错误（{step}）', { step }), unit: 'count',
        series: dependencies.map(([category, trend]) => ({ label: categoryNames[category],
          values: countsSince(trend, point => point.errors) })) }))
    }
  }
  return charts.map(chart => ({ ...chart, group: 'overview' }))
}

function storageText(storage) {
  if (!storage.persistent) return t('历史存储不可用，只显示内存中最近约 1 小时')
  return t('历史存储 {used} / {max} · 保留 {days} 天', { used: formatValue(storage.storageBytes, 'bytes'),
    max: formatValue(storage.maxStorageBytes, 'bytes'), days: storage.retentionDays })
}

async function refresh() {
  const period = $('period').value
  const requestId = ++refreshRequest
  try {
    const [resources, storage, trends] = await Promise.all([
      request(`metrics/resources?period=${period}`),
      request('metrics/storage'),
      request(`metrics/operations/trends?period=${period}`).catch(() => null)
    ])
    if (requestId !== refreshRequest) return true
    for (const chart of charts) chart.destroy()
    const resourceList = [...overviewCharts(trends, resources), ...resourceCharts(resources)]
    charts = resourceList
    for (const group of GROUPS) {
      fillSection(group.id, resourceList.filter(chart => chart.group === group.id))
    }
    $('empty').hidden = resourceList.length > 0
    $('empty').textContent = t('还没有资源指标：指标每分钟采样一次，请稍后刷新')
    $('storage').textContent = storageText(storage)
    $('notice').hidden = true
    return true
  } catch (error) {
    if (requestId !== refreshRequest) return true
    $('notice').textContent = error.message
    $('notice').hidden = false
    if (error.message === DISABLED_MESSAGE) {
      disabled = true
      poller?.stop()
    }
    return false
  }
}

const requested = new URLSearchParams(window.location.search).get('period')
if (PERIODS.has(requested)) $('period').value = requested
$('period').addEventListener('change', () => {
  const url = new URL(window.location.href)
  url.searchParams.set('period', $('period').value)
  window.history.replaceState(null, '', url)
  poller?.restart()
  refresh()
})
$('refresh').addEventListener('click', refresh)
$('expand-all').addEventListener('click', () => setAllOpen(true))
$('collapse-all').addEventListener('click', () => setAllOpen(false))
buildSections()
await initializeEmbeddedAuth()
await refresh()
if (!disabled) poller = poll(refresh, REFRESH_MS)
