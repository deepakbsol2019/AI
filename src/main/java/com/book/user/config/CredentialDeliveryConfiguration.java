package com.book.user.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Forgot-password delivery never executes SMTP on the public request thread. */
@Configuration(proxyBeanMethods = false)
public class CredentialDeliveryConfiguration {
    @Bean(name = "userCredentialMailExecutor")
    ThreadPoolTaskExecutor userCredentialMailExecutor(Environment environment) {
        int threads = environment.getProperty("app.user.mail.delivery-threads", Integer.class, 2);
        int capacity = environment.getProperty("app.user.mail.delivery-queue-capacity", Integer.class, 100);
        if (threads < 1 || threads > 2 || capacity < 1 || capacity > 100) {
            throw new IllegalStateException("Credential delivery requires 1–2 threads and a queue capacity of 1–100.");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("credential-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
