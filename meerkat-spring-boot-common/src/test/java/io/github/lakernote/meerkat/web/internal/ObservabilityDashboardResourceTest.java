package io.github.lakernote.meerkat.web.internal;

import org.junit.jupiter.api.Test;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityDashboardResourceTest {
    @Test
    void bundlesTheDashboardEntryAndItsLocalAssets() throws IOException {
        var classLoader = getClass().getClassLoader();
        var page = classLoader.getResourceAsStream("META-INF/resources/meerkat/index.html");
        var metricsPage = classLoader.getResourceAsStream("META-INF/resources/meerkat/metrics.html");
        var script = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/dashboard.js");
        var metricsScript = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/metrics.js");
        var style = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/dashboard.css");

        assertThat(page).isNotNull();
        assertThat(metricsPage).isNotNull();
        assertThat(script).isNotNull();
        assertThat(metricsScript).isNotNull();
        assertThat(style).isNotNull();
        String pageContent = new String(page.readAllBytes(), StandardCharsets.UTF_8);
        String metricsPageContent = new String(metricsPage.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(pageContent)
                .contains("Meerkat", "src=\"assets/dashboard.js", "href=\"stats/\">运行指标",
                        "Trace 诊断", "正常请求不会显示在此列表中", "内存分配量", "CPU 时间", "完成时间", "id=\"result-filter\" class=\"segmented\"",
                        "表格可横向滚动查看全部字段",
                        "<button id=\"clear-history\" class=\"quiet-button\" type=\"button\" hidden>")
                .doesNotContain("href=\"/meerkat", "src=\"/meerkat");
        assertThat(metricsPageContent)
                .contains("src=\"../assets/metrics.js", "href=\"../\"", "href=\"./\">指标趋势",
                        "href=\"../stats/\">调用排行")
                .doesNotContain("href=\"/meerkat", "src=\"/meerkat");
        assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                .contains("from './common.js?v=",
                        "initializeEmbeddedAuth().then(() => Promise.all([loadPermissions(), refresh()]))",
                        "$('clear-history').hidden = !permissions.canClear",
                        "kind: resultFilter", "params.set('before', pageCursors[page])",
                        "traces.scanLimited ? t('继续搜索') : t('下一页')", "notFoundMessage: t('这条 Trace 已不在保留范围内')",
                        "formatAllocatedBytes(trace.allocatedBytes)", "formatAllocatedBytes(detail.allocatedBytes)",
                        "内存分配量", "不是当前堆占用", "条诊断 Trace",
                        "addFact($('facts'), t('状态')", "foldRepeats(", "formatCpu(trace.cpuTimeMs)")
                .doesNotContain("const api = ", "async function request(", "function node(");
        try (var common = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/common.js")) {
            assertThat(new String(common.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("new URL('../../api/meerkat/', import.meta.url)",
                            "new URL(path.replace(/^\\/+/, ''), api)",
                            "meerkat:auth-request", "meerkat:auth-response",
                            "headers.set('Authorization', 'Bearer ' + token)", "X-XSRF-TOKEN",
                            "export function showNotice(")
                    .doesNotContain("innerHTML");
        }
        assertThat(new String(metricsScript.readAllBytes(), StandardCharsets.UTF_8))
                .contains("overviewCharts(trends, resources)", "meerkat.executor.rejected", "request(`metrics/operations/trends?period=${period}`)",
                        "meter: 'hikaricp.connections.acquire', stat: 'total', per: 'count'",
                        "request(`metrics/resources?period=${period}`)", "request('metrics/storage')",
                        "历史存储不可用",
                        "OPEN_STATE_KEY", "buildSections()", "fillSection(")
                .doesNotContain("innerHTML", "const api = '/api/", "operationCharts", "custom", "include");
        page.close();
        metricsPage.close();
        script.close();
        metricsScript.close();
        style.close();
    }

    @Test
    void bundlesTheStatisticsPageWithRelativeLinks() throws IOException {
        var classLoader = getClass().getClassLoader();
        try (var page = classLoader.getResourceAsStream("META-INF/resources/meerkat/stats.html");
             var script = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/stats.js");
             var style = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/stats.css");
             var index = classLoader.getResourceAsStream("META-INF/resources/meerkat/index.html");
             var metrics = classLoader.getResourceAsStream("META-INF/resources/meerkat/metrics.html");
             var common = classLoader.getResourceAsStream(
                     "META-INF/resources/meerkat/assets/metrics-common.js");
             var dashboard = classLoader.getResourceAsStream(
                     "META-INF/resources/meerkat/assets/dashboard.js")) {
            assertThat(style).isNotNull();
            assertThat(new String(page.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("src=\"../assets/stats.js", "src=\"../assets/uplot.min.js", "href=\"../\"",
                            "href=\"../metrics/\">指标趋势", "<option value=\"30d\">最近 30 天</option>",
                            "<button id=\"reset-stats\" class=\"quiet-button\" type=\"button\" hidden>")
                    .doesNotContain("href=\"/meerkat", "src=\"/meerkat");
            assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("request(`metrics/operations?window=${encodeURIComponent($('window').value)}`)",
                            "request(`metrics/operations/trend?category=", "LONG_RANGE_REFRESH_MS",
                            "url.searchParams.set('window', $('window').value)", "link.href = `../?q=",
                            "request('/metrics/operations/reset', { method: 'POST' }",
                            "$('reset-stats').hidden = !permissions.canClear", "next.collecting")
                    .doesNotContain("innerHTML", "const api = '/api/", "traceQuery");
            assertThat(new String(common.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("from './common.js?v=", "notFoundMessage: DISABLED_MESSAGE", "export function lineChart(")
                    .doesNotContain("innerHTML");
            assertThat(new String(index.readAllBytes(), StandardCharsets.UTF_8)).contains("href=\"stats/\"");
            assertThat(new String(metrics.readAllBytes(), StandardCharsets.UTF_8)).contains("href=\"../stats/\"");
            assertThat(new String(dashboard.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("new URLSearchParams(window.location.search)", "applyLinkedFilters()\nbind()");
        }
    }

    @Test
    void loadsTheLanguageSwitchBeforeEveryPageScript() throws IOException {
        var classLoader = getClass().getClassLoader();
        for (var name : new String[] {"index.html", "stats.html", "live.html", "metrics.html", "capture.html", "tools.html"}) {
            try (var page = classLoader.getResourceAsStream("META-INF/resources/meerkat/" + name)) {
                assertThat(page).as(name).isNotNull();
                String content = new String(page.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(content).as(name)
                        .contains("assets/i18n.js?v=", "<button id=\"lang-toggle\" class=\"lang-toggle\" type=\"button\"")
                        .containsPattern("href=\"(\\.\\./tools/|tools/|\\./)\">工具箱</a>")
                        .doesNotContain("src=\"/meerkat");
                assertThat(content.indexOf("assets/i18n.js")).as(name)
                        .isLessThan(content.indexOf("<script type=\"module\""));
            }
        }
        try (var i18n = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/i18n.js");
             var stats = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/stats.js")) {
            assertThat(i18n).isNotNull();
            assertThat(new String(i18n.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("'meerkat.lang'", "navigator.language", "window.t = t", "getElementById('lang-toggle')",
                            "'Trace 诊断': 'Traces'", "'重置统计': 'Reset stats'",
                            "'" + OperationStats.OVERFLOW_NAME + "': '(Other: over the tracked-operation limit)'")
                    .doesNotContain("innerHTML");
            assertThat(new String(stats.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("const OVERFLOW_NAME = '" + OperationStats.OVERFLOW_NAME + "'", "displayName(row.name)");
        }
    }

    @Test
    void buildsTheToolboxWithoutInnerHtmlAndTranslatesIt() throws IOException {
        var classLoader = getClass().getClassLoader();
        try (var page = classLoader.getResourceAsStream("META-INF/resources/meerkat/tools.html");
             var script = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/tools.js");
             var i18n = classLoader.getResourceAsStream("META-INF/resources/meerkat/assets/i18n.js")) {
            assertThat(page).isNotNull();
            assertThat(new String(page.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("assets/tools.js?v=", "assets/tools.css?v=", "<a class=\"active\" href=\"./\">工具箱</a>");
            assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("'../../api/meerkat/tools/'", "canOperate")
                    .doesNotContain("innerHTML", "const api = '/api/");
            assertThat(new String(i18n.readAllBytes(), StandardCharsets.UTF_8)).contains("'工具箱': 'Toolbox'");
        }
    }

    @Test
    void redirectsEntryPagesToCanonicalTrailingSlashUrls() {
        var traceRedirect = new ObservabilityDashboardController().redirectToIndex();
        var metricsRedirect = new MetricsController((OperationStats) null, null, null, () -> true).redirectToMetrics();

        assertThat(traceRedirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(traceRedirect.getHeaders().getLocation()).hasToString("meerkat/");
        assertThat(metricsRedirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(metricsRedirect.getHeaders().getLocation()).hasToString("metrics/");
    }

    @Test
    void keepsThreadIdentityVisibleInTimelines() throws IOException {
        try (var script = getClass().getClassLoader()
                .getResourceAsStream("META-INF/resources/meerkat/assets/dashboard.js")) {
            assertThat(script).isNotNull();
            assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("node('span', 'timeline-thread-heading', t('线程', null, 'one'))",
                            "node('span', `timeline-thread${threadClass}`, thread.key)",
                            "const role = thread.info.isEntry ? t('入口线程') : t('子线程')",
                            "container.insertBefore(legend, scale)");
        }
    }
}
