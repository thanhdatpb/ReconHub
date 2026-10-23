package vn.reconhub.batch.matching;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import vn.reconhub.core.fees.FeeRevalidation;

/**
 * feeRevalidationJob: revalidateFeeStep. Launched when a fee schedule version is approved; {@code scheduleId} is the
 * identifying parameter, so each version is re-checked once and a failed run is restarted rather than repeated.
 */
@Configuration
public class FeeRevalidationSteps {
    public static final String JOB = "feeRevalidationJob";

    @Bean
    Job feeRevalidationJob(JobRepository repo, Step revalidateFeeStep) {
        return new JobBuilder(JOB, repo).start(revalidateFeeStep).build();
    }

    @Bean
    Step revalidateFeeStep(JobRepository repo, PlatformTransactionManager tx, FeeRevalidation revalidation) {
        return new StepBuilder("revalidateFeeStep", repo).tasklet((contribution, ctx) -> {
            var step = ctx.getStepContext().getStepExecution();
            FeeRevalidation.Result r = revalidation.revalidate(step.getJobParameters().getLong("scheduleId"), MatchingSteps.run(step));
            for (int i = 0; i < r.checked(); i++) contribution.incrementReadCount();
            contribution.incrementWriteCount(r.resolved());
            return RepeatStatus.FINISHED;
        }, tx).build();
    }
}
