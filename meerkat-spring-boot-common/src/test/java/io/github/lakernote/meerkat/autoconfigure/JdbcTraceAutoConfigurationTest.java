package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.integration.jdbc.internal.JdbcTraceQueryExecutionListener;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcTraceAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcTraceAutoConfiguration.class))
            .withBean(OperationStats.class, () -> new OperationStats(10));

    @Test
    void recordsSqlOnceWhenADelegatingDataSourceWrapsAnotherDataSourceBean() {
        runner.withUserConfiguration(RoutingConfiguration.class).run(context -> {
            DataSource routing = context.getBean("routingDataSource", DataSource.class);
            try (Connection connection = routing.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }

            assertThat(sqlCount(context.getBean(OperationStats.class))).isEqualTo(1);
        });
    }

    @Test
    void addsTheListenerToAnExistingProxyDataSourceOnlyOnce() {
        runner.run(context -> {
                    JdbcTraceQueryExecutionListener listener = context.getBean(JdbcTraceQueryExecutionListener.class);
                    ProxyDataSource alreadyListening = ProxyDataSourceBuilder.create(driverDataSource())
                            .listener(listener).build();

                    Object processed = context.getBean("jdbcTraceDataSourcePostProcessor",
                                    org.springframework.beans.factory.config.BeanPostProcessor.class)
                            .postProcessAfterInitialization(alreadyListening, "other");

                    assertThat(processed).isSameAs(alreadyListening);
                    assertThat(alreadyListening.getProxyConfig().getQueryListener().getListeners())
                            .filteredOn(chained -> chained == listener).hasSize(1);
                    try (Connection connection = alreadyListening.getConnection();
                         Statement statement = connection.createStatement()) {
                        statement.execute("SELECT 1");
                    }
                    assertThat(sqlCount(context.getBean(OperationStats.class))).isEqualTo(1);
                });
    }

    @Test
    void leavesJdbcObservationsAloneWithoutDatasourceMicrometer() {
        runner.run(context -> assertThat(context).doesNotHaveBean("jdbcObservationDeduplication"));
    }

    private static long sqlCount(OperationStats stats) {
        return stats.snapshot(OperationStats.Window.TODAY).categories().stream()
                .filter(category -> category.category() == OperationStats.Category.SQL)
                .flatMap(category -> category.rows().stream())
                .mapToLong(row -> row.count())
                .sum();
    }

    private static DataSource driverDataSource() {
        try {
            Statement statement = mock(Statement.class);
            when(statement.execute(anyString())).thenReturn(false);
            Connection connection = mock(Connection.class);
            when(connection.createStatement()).thenReturn(statement);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return dataSource;
        } catch (SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class RoutingConfiguration {
        @org.springframework.context.annotation.Bean
        DataSource targetDataSource() {
            return driverDataSource();
        }

        @org.springframework.context.annotation.Bean
        DelegatingDataSource routingDataSource(
                @org.springframework.beans.factory.annotation.Qualifier("targetDataSource") DataSource target) {
            return new DelegatingDataSource(target);
        }
    }

    /** Like Spring's AbstractRoutingDataSource or DelegatingDataSource: another DataSource bean does the work. */
    public static class DelegatingDataSource implements DataSource {
        private final DataSource target;

        DelegatingDataSource(DataSource target) {
            this.target = target;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return target.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return target.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
