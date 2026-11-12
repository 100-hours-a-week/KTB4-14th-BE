package com.audigo.domain.chat.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.TimestampJdbcType;

/**
 * Chat DATETIME(6) values represent UTC wall-clock fields, not JVM-local instants.
 * JDBC 4.2 LocalDateTime binding avoids Timestamp.valueOf/default-zone conversions.
 * Retains JDBC TIMESTAMP metadata so Hibernate's temporal comparisons/aggregates work.
 */
public class UtcLocalDateTimeJdbcType extends TimestampJdbcType {
    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new BasicBinder<>(javaType, this) {
            @Override protected void doBind(PreparedStatement statement, X value, int index, WrapperOptions options) throws SQLException {
                statement.setObject(index, javaType.unwrap(value, LocalDateTime.class, options), Types.TIMESTAMP);
            }
            @Override protected void doBind(CallableStatement statement, X value, String name, WrapperOptions options) throws SQLException {
                statement.setObject(name, javaType.unwrap(value, LocalDateTime.class, options), Types.TIMESTAMP);
            }
        };
    }

    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
        return new BasicExtractor<>(javaType, this) {
            @Override protected X doExtract(ResultSet result, int index, WrapperOptions options) throws SQLException {
                return javaType.wrap(result.getObject(index, LocalDateTime.class), options);
            }
            @Override protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
                return javaType.wrap(statement.getObject(index, LocalDateTime.class), options);
            }
            @Override protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
                return javaType.wrap(statement.getObject(name, LocalDateTime.class), options);
            }
        };
    }
}
