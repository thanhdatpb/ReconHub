package vn.shop.oms.db;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.function.Function;

/** One SqlSessionFactory for the webapp; the connections come from Tomcat's JNDI pool (jdbc/OmsDB). */
public final class Db {
    private static final SqlSessionFactory FACTORY = build();

    private Db() {}

    private static SqlSessionFactory build() {
        try (InputStream in = Resources.getResourceAsStream("mybatis-config.xml")) {
            return new SqlSessionFactoryBuilder().build(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs the work in one transaction: commit on success, rollback on any exception. */
    public static <T> T tx(Function<OrderMapper, T> work) {
        try (SqlSession session = FACTORY.openSession(false)) {
            T result = work.apply(session.getMapper(OrderMapper.class));
            session.commit();
            return result;
        }
    }
}
