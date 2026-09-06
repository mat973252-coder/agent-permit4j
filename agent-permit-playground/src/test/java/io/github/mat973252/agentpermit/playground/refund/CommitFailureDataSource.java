package io.github.mat973252.agentpermit.playground.refund;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Offline fault injection at one chosen local commit, before or after the real commit. */
final class CommitFailureDataSource implements DataSource {
  private final DataSource delegate;
  private final int failAt;
  private final boolean afterCommit;
  private final AtomicInteger commits = new AtomicInteger();

  CommitFailureDataSource(DataSource delegate, int failAt, boolean afterCommit) {
    this.delegate = delegate;
    this.failAt = failAt;
    this.afterCommit = afterCommit;
  }

  private Connection wrap(Connection connection) {
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, args) -> {
          if (method.getName().equals("commit") && commits.incrementAndGet() == failAt) {
            if (afterCommit) {
              connection.commit();
            }
            throw new SQLException("synthetic commit response unavailable");
          }
          try {
            return method.invoke(connection, args);
          } catch (InvocationTargetException exception) {
            throw exception.getCause();
          }
        });
  }

  @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
  @Override public Connection getConnection(String user, String password) throws SQLException {
    return wrap(delegate.getConnection(user, password));
  }
  @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
  @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
  @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
  @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
  @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
  @Override public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
  @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
}
