package vn.reconhub.batch.matching;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Enums.LineStatus;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.ai.SuggestionService;
import vn.reconhub.core.matching.MatchingEngine;
import vn.reconhub.core.matching.MatchingEngine.Outcome;
import vn.reconhub.core.matching.PostMatchChecks;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.PaymentLineRepository;

import java.time.Instant;
import java.time.LocalDate;

/**
 * matchingJob: matchLinesStep (every NEW line through the rule tiers) → detectUnpaidStep → aiSuggestStep.
 * feeValidationJob: validateFeeStep → aiSuggestStep. Both take {@code runDate} and {@code at} (the run's clock time).
 */
@Configuration
public class MatchingSteps {
    public static final String MATCHING_JOB = "matchingJob";
    public static final String FEE_JOB = "feeValidationJob";

    private final MatchingEngine engine;
    private final PostMatchChecks checks;
    private final PaymentLineRepository lines;
    private final ExceptionRepository exceptions;
    private final SuggestionService suggestions;
    private final ReconProperties props;
    private final JdbcTemplate jdbc;

    public MatchingSteps(MatchingEngine engine, PostMatchChecks checks, PaymentLineRepository lines, ExceptionRepository exceptions,
                         SuggestionService suggestions, ReconProperties props, JdbcTemplate jdbc) {
        this.engine = engine;
        this.checks = checks;
        this.lines = lines;
        this.exceptions = exceptions;
        this.suggestions = suggestions;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** The run context the engine records on match groups and exceptions, e.g. actor "matchingJob #42". */
    static MatchingEngine.Run run(StepExecution step) {
        JobParameters p = step.getJobParameters();
        return new MatchingEngine.Run(Instant.parse(p.getString("at")), LocalDate.parse(p.getString("runDate")),
                step.getJobExecution().getJobInstance().getJobName() + " #" + step.getJobExecutionId());
    }

    @Bean
    Job matchingJob(JobRepository repo, Step matchLinesStep, Step detectUnpaidStep, Step aiSuggestStep) {
        return new JobBuilder(MATCHING_JOB, repo).start(matchLinesStep).next(detectUnpaidStep).next(aiSuggestStep).build();
    }

    @Bean
    Job feeValidationJob(JobRepository repo, Step validateFeeStep, Step aiSuggestStep) {
        return new JobBuilder(FEE_JOB, repo).start(validateFeeStep).next(aiSuggestStep).build();
    }

    /** Line ids are read up front; a restart simply reads the lines still NEW. */
    @Bean
    Step matchLinesStep(JobRepository repo, PlatformTransactionManager tx, ListItemReader<Long> newLineReader, ItemWriter<Long> matchWriter) {
        return new StepBuilder("matchLinesStep", repo).<Long, Long>chunk(100, tx)
                .reader(newLineReader)
                .writer(matchWriter)
                .build();
    }

    @Bean
    @StepScope
    ListItemReader<Long> newLineReader() {
        return new ListItemReader<>(jdbc.queryForList("select id from payment_lines where status = 'NEW' order by id", Long.class));
    }

    /** Counts each outcome in the step context so the batch screen can show how lines were matched. */
    @Bean
    @StepScope
    ItemWriter<Long> matchWriter(@Value("#{stepExecution}") StepExecution step) {
        MatchingEngine.Run run = run(step);
        return chunk -> {
            ExecutionContext counts = step.getExecutionContext();
            for (Long id : chunk) {
                PaymentLine line = lines.findById(id).orElse(null);
                if (line == null || line.getStatus() != LineStatus.NEW) continue;
                Outcome outcome = engine.match(line, run);
                counts.putLong(outcome.name(), counts.getLong(outcome.name(), 0L) + 1);
            }
        };
    }

    @Bean
    Step detectUnpaidStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("detectUnpaidStep", repo).tasklet((contribution, ctx) -> {
            int raised = checks.detectUnpaid(run(ctx.getStepContext().getStepExecution()));
            contribution.incrementWriteCount(raised);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Step validateFeeStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("validateFeeStep", repo).tasklet((contribution, ctx) -> {
            int raised = checks.validateFees(run(ctx.getStepContext().getStepExecution()));
            contribution.incrementWriteCount(raised);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    /**
     * Attaches a suggestion to each new exception. Rules only by default; the LLM is asked when
     * {@code reconhub.ai.suggest-during-batch} is on. A person editing the same exception wins (the row is skipped).
     */
    @Bean
    Step aiSuggestStep(JobRepository repo, PlatformTransactionManager tx, ListItemReader<String> unsuggestedReader) {
        return new StepBuilder("aiSuggestStep", repo).<String, String>chunk(20, tx)
                .reader(unsuggestedReader)
                .writer(chunk -> {
                    for (String id : chunk) {
                        ReconException e = exceptions.findById(id).orElse(null);
                        if (e == null || e.getAi() != null) continue;
                        e.setAi(props.getAi().isSuggestDuringBatch() ? suggestions.suggest(e, null) : suggestions.heuristic(e));
                    }
                })
                .faultTolerant()
                .skip(OptimisticLockingFailureException.class)
                .skipLimit(50)
                .build();
    }

    @Bean
    @StepScope
    ListItemReader<String> unsuggestedReader() {
        return new ListItemReader<>(jdbc.queryForList(
                "select id from recon_exceptions where ai is null and status in ('OPEN', 'IN_REVIEW') order by id", String.class));
    }
}
