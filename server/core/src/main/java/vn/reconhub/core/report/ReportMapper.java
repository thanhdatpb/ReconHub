package vn.reconhub.core.report;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-side SQL. Reports join many tables and aggregate, so they are plain SQL in MyBatis
 * (see resources/mapper/ReportMapper.xml) rather than JPA entity graphs.
 */
@Mapper
public interface ReportMapper {

    List<Rows.DayStat> dayStats(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("sources") List<String> sources);

    List<Rows.ExceptionRow> searchExceptions(@Param("q") ExceptionQuery query);

    Rows.ExceptionCounts exceptionCounts(@Param("now") Instant now);

    List<Rows.SourceAmount> openBySource();

    Rows.FeeDiffTotal feeDiffSince(@Param("since") Instant since);

    List<Rows.MatchGroupRow> matchGroups(@Param("date") LocalDate date, @Param("source") String source);

    List<Rows.AgingRow> aging(@Param("asOf") LocalDate asOf, @Param("zone") String zone);

    List<Rows.JournalRow> journal(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                  @Param("fromTs") Instant fromTs, @Param("toTs") Instant toTs, @Param("zone") String zone);

    List<Rows.FileArrivalRow> filesReceivedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
