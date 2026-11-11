package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.integration.jdbc.internal.JdbcTraceQueryExecutionListener;
import io.github.lakernote.meerkat.integration.jdbc.internal.SpringAwareJdbcProxyFactory;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import net.ttddyy.dsproxy.proxy.ProxyJdbcObject;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 为活动 Trace 中的 JDBC 调用记录轻量 SQL 子节点并汇总 SQL 统计，不创建 Micrometer Observation 或 Meter。
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, ProxyDataSourceBuilder.class})
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "meerkat.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "jdbc.datasource-proxy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JdbcTraceAutoConfiguration {
    @Bean
    JdbcTraceQueryExecutionListener jdbcTraceQueryExecutionListener(ObjectProvider<OperationStats> operationStats,
                                                                    ListableBeanFactory beanFactory) {
        return new JdbcTraceQueryExecutionListener(operationStats.getIfAvailable(),
                () -> beanFactory.getBeanNamesForType(DataSource.class, false, false).length > 1);
    }

    @Bean
    static BeanPostProcessor jdbcTraceDataSourcePostProcessor(
            ObjectProvider<JdbcTraceQueryExecutionListener> listenerProvider) {
        return new JdbcTraceDataSourcePostProcessor(listenerProvider);
    }

    private static final class JdbcTraceDataSourcePostProcessor implements BeanPostProcessor {
        private final ObjectProvider<JdbcTraceQueryExecutionListener> listenerProvider;

        private JdbcTraceDataSourcePostProcessor(ObjectProvider<JdbcTraceQueryExecutionListener> listenerProvider) {
            this.listenerProvider = listenerProvider;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof DataSource dataSource)) return bean;

            JdbcTraceQueryExecutionListener listener = listenerProvider.getIfAvailable();
            if (listener == null) return bean;
            if (bean instanceof ProxyDataSource proxyDataSource) {
                proxyDataSource.addListener(listener);
                return bean;
            }
            if (bean instanceof ProxyJdbcObject) return bean;

            String dataSourceName = StringUtils.hasText(beanName) ? beanName : "dataSource";
            ProxyDataSourceBuilder builder = ProxyDataSourceBuilder.create(dataSourceName, dataSource)
                    .listener(listener)
                    .jdbcProxyFactory(new SpringAwareJdbcProxyFactory());
            // 流量抓取需要记录查询结果；未抓取的结果集由工厂原样返回。
            return builder.proxyResultSet().buildProxy();
        }
    }
}
