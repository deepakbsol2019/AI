package com.book.spreadsheet.upload;

import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SpreadsheetUploadConfiguration {
    /** Defaults leave connections for auth/API work in the existing ten-connection Hikari pool. */
    @Bean("spreadsheetBatchExecutor")
    public ThreadPoolTaskExecutor spreadsheetBatchExecutor(
            @Value("${app.spreadsheet.batch-pool-size:2}") int threads,
            @Value("${app.spreadsheet.batch-queue-capacity:4}") int queue) {
        if(threads<1 || threads>4 || queue<1 || queue>32) throw new IllegalArgumentException("Spreadsheet workers require 1–4 threads and queue capacity 1–32.");
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);executor.setMaxPoolSize(threads);executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("spreadsheet-batch-");
        executor.setRejectedExecutionHandler((task,pool)->{
            if(pool.isShutdown())throw new RejectedExecutionException("Spreadsheet persistence is shutting down.");
            task.run(); // bounded caller-runs backpressure; the coordinator holds no transaction.
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
