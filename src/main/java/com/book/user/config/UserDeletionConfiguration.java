package com.book.user.config;
import com.book.user.service.UserDeletionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.core.env.Environment;
@Configuration(proxyBeanMethods=false) @EnableScheduling
public class UserDeletionConfiguration {
    private final UserDeletionService jobs;private final Environment settings;
    public UserDeletionConfiguration(UserDeletionService jobs,Environment settings){this.jobs=jobs;this.settings=settings;}
    @Scheduled(fixedDelayString="${app.user.deletion.poll-ms:5000}",initialDelayString="${app.user.deletion.poll-ms:5000}")
    public void process(){if(!settings.getProperty("app.user.deletion.enabled",Boolean.class,true))return;
        try{jobs.processAvailable();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(UserDeletionConfiguration.class).warn("USER_DELETION_WORKER_UNAVAILABLE: check the user-management migration and database availability");}}
}
