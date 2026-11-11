// Shared by the call ranking, metric trend and live pages: metric labels, formatting and uPlot line charts.
import { node, request as apiRequest } from './common.js?v=20261111.2'

export { $, api, initializeEmbeddedAuth, kindName, node, poll, showNotice } from './common.js?v=20261111.2'
export const DISABLED_MESSAGE = t('运行指标未启用：请确认 meerkat.metrics.enabled 为 true')
export const categoryNames = {
  HTTP: t('HTTP 接口'), SQL: 'SQL', REMOTE: t('远程调用'), REDIS: 'Redis',
  JOB: t('定时任务'), KAFKA_CONSUMER: t('Kafka 消费'), KAFKA_PRODUCER: t('Kafka 发送')
}
const COLORS = ['#4169c9', '#08a68a', '#df8d28', '#8a62c2', '#db5d68', '#2696ad', '#5d9d4d', '#bd6e31',
  '#3955a5', '#00a6a6', '#e27730', '#a0559a', '#c94747', '#197aa6', '#799b34', '#8f6637']

/** Like the shared request, but a 404 means the metrics endpoints are disabled. */
export function request(path, options = {}, deniedMessage = t('没有查看运行指标的权限，请登录或检查 ObservabilityAccessPolicy')) {
  return apiRequest(path, { notFoundMessage: DISABLED_MESSAGE, ...options }, deniedMessage)
}

export function formatMs(value) {
  const ms = Number(value || 0)
  if (ms >= 1000) return `${(ms / 1000).toLocaleString(undefined, { maximumFractionDigits: ms >= 100000 ? 0 : 2 })} s`
  if (ms >= 100) return `${Math.round(ms).toLocaleString()} ms`
  if (ms >= 1) return `${ms.toFixed(1)} ms`
  return `${ms.toFixed(2)} ms`
}

export function formatBytes(bytes) {
  if (bytes == null || bytes < 0) return '—'
  const units = ['B', 'KiB', 'MiB', 'GiB']
  let value = Number(bytes)
  let index = 0
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++ }
  return `${value.toFixed(index === 0 || value >= 100 ? 0 : 1)} ${units[index]}`
}

export function formatValue(value, unit) {
  if (value == null || !Number.isFinite(value)) return '—'
  switch (unit) {
    case 'ms': return formatMs(value)
    case 'duration': {
      const abs = Math.abs(value)
      if (abs >= 86400000) return `${(value / 86400000).toFixed(1)} d`
      if (abs >= 3600000) return `${(value / 3600000).toFixed(1)} h`
      if (abs >= 60000) return `${Math.round(value / 60000)} min`
      return `${Math.round(value / 1000)} s`
    }
    case 'percent': return `${value.toFixed(value < 10 ? 1 : 0)}%`
    case 'bytes': {
      const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
      let scaled = Math.abs(value)
      let index = 0
      while (scaled >= 1024 && index < units.length - 1) { scaled /= 1024; index++ }
      return `${(Math.sign(value) * scaled).toFixed(scaled >= 100 || index === 0 ? 0 : 1)} ${units[index]}`
    }
    default: {
      const abs = Math.abs(value)
      return abs >= 100 || Number.isInteger(value) ? Math.round(value).toLocaleString() : value.toFixed(abs >= 10 ? 1 : 2)
    }
  }
}

export function stepLabel(stepMillis) {
  if (stepMillis >= 3600000) return t('每小时')
  if (stepMillis >= 600000) return t('每 10 分钟')
  return t('每分钟')
}

function axisTime(stepMillis, spanMillis) {
  return value => {
    const date = new Date(value * 1000)
    const time = date.toLocaleTimeString(MeerkatI18n.locale, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
    if (spanMillis > 2 * 86400000) return stepMillis >= 3600000 && spanMillis > 8 * 86400000
      ? `${date.getMonth() + 1}/${date.getDate()}` : `${date.getMonth() + 1}/${date.getDate()} ${time}`
    return time
  }
}

/**
 * Renders one chart card. {@code series} items are {label, values}; values align with {@code timestamps} (ms) and
 * may contain null. Returns {card, destroy}; series without any value are left out.
 */
/** Counts per bucket, with buckets before the covered range left empty instead of drawn as zero. */
export function countsSince(trend, pick) {
  const since = Date.parse(trend.since)
  return trend.points.map(point => point.timestamp + trend.stepMillis <= since ? null : pick(point))
}

function hasIsolatedValue(values) {
  return values.some((value, index) => value != null && values[index - 1] == null && values[index + 1] == null)
}

export function lineChart({ title, hint, unit, timestamps, stepMillis, series, height = 220, empty = t('所选时间范围内暂无数据') }) {
  const card = node('article', 'metric-chart')
  const head = node('div', 'metric-chart-head')
  head.append(node('h2', '', title))
  if (hint) head.append(node('span', 'metric-chart-hint', hint))
  card.append(head)
  const shown = series.filter(item => item.values.some(value => value != null && Number.isFinite(value)))
  if (!shown.length || typeof uPlot !== 'function') {
    card.append(node('div', 'metric-empty', typeof uPlot === 'function' ? empty : t('图表组件加载失败')))
    return { card, destroy() {} }
  }
  const plotHost = node('div', 'metric-plot')
  plotHost.setAttribute('role', 'img')
  plotHost.setAttribute('aria-label', t('{title}趋势图', { title }))
  const legend = node('div', 'metric-legend')
  card.append(plotHost, legend)
  const times = timestamps.map(value => value / 1000)
  const legendValues = []
  const latest = item => {
    for (let index = item.values.length - 1; index >= 0; index--) {
      if (item.values[index] != null) return item.values[index]
    }
    return null
  }
  shown.forEach((item, index) => {
    const button = node('button', 'metric-legend-item')
    button.type = 'button'
    button.title = item.label
    button.setAttribute('aria-pressed', 'true')
    const marker = node('span', 'metric-legend-marker')
    marker.setAttribute('aria-hidden', 'true')
    marker.style.setProperty('--series-color', COLORS[index % COLORS.length])
    const value = node('span', 'metric-legend-value', formatValue(latest(item), unit))
    button.append(marker, node('span', 'metric-legend-label', item.label), value)
    legend.append(button)
    legendValues.push(value)
    button.addEventListener('click', () => {
      const visible = button.getAttribute('aria-pressed') !== 'true'
      button.setAttribute('aria-pressed', String(visible))
      button.classList.toggle('is-hidden', !visible)
      plot.setSeries(index + 1, { show: visible })
    })
  })
  const span = timestamps.length ? timestamps.at(-1) - timestamps[0] : 0
  const plot = new uPlot({
    width: Math.max(320, plotHost.clientWidth || 600),
    height,
    class: 'metric-uplot',
    scales: { x: { time: true }, y: { range: (u, min, max) => {
      if (!Number.isFinite(min) || !Number.isFinite(max)) return [0, 1]
      const low = Math.min(0, min)
      return [low, max > low ? max * 1.05 : low + 1]
    } } },
    axes: [
      { stroke: '#77869a', grid: { stroke: '#edf1f6', width: 1 }, ticks: { stroke: '#e4e9f0' },
        values: (u, values) => values.map(axisTime(stepMillis, span)) },
      { stroke: '#77869a', grid: { stroke: '#edf1f6', width: 1 }, ticks: { stroke: '#e4e9f0' }, size: 70,
        values: (u, values) => values.map(value => formatValue(value, unit)) }
    ],
    series: [{ label: t('时间') }, ...shown.map((item, index) => ({
      label: item.label, stroke: COLORS[index % COLORS.length], width: 2, spanGaps: false,
      // A value with gaps on both sides draws no line segment, so mark it with a dot.
      points: { show: hasIsolatedValue(item.values), size: 5, fill: COLORS[index % COLORS.length] }
    }))],
    legend: { show: false },
    hooks: { setCursor: [u => {
      const index = u.cursor.idx
      shown.forEach((item, position) => {
        legendValues[position].textContent = formatValue(index == null ? latest(item) : item.values[index], unit)
      })
      hover.textContent = index == null ? t('最新值') : new Date(timestamps[index]).toLocaleString(MeerkatI18n.locale)
    }] }
  }, [times, ...shown.map(item => item.values)], plotHost)
  const foot = node('div', 'metric-chart-foot')
  const hover = node('span', 'metric-hover-time', t('最新值'))
  foot.append(node('span', '', t('{step}一个点 · 点击图例开关曲线', { step: stepLabel(stepMillis) })), hover)
  card.append(foot)
  const resizeObserver = new ResizeObserver(entries => {
    const width = Math.floor(entries[0].contentRect.width)
    if (width > 0 && width !== plot.width) plot.setSize({ width, height })
  })
  resizeObserver.observe(plotHost)
  return { card, destroy() { resizeObserver.disconnect(); plot.destroy() } }
}
