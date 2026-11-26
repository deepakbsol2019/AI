package com.book.user.config;
import java.util.*;
import com.book.storage.TenantStoragePaths;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** Opt-in operator backfill. Creates verified tenant-ID directories only; never adopts shared files. */
@Component
public class IndividualStorageBackfill implements ApplicationRunner {
    private final JdbcTemplate jdbc;private final TenantStoragePaths storage;private final Environment settings;
    public IndividualStorageBackfill(JdbcTemplate jdbc,TenantStoragePaths storage,Environment settings){this.jdbc=jdbc;this.storage=storage;this.settings=settings;}
    public record Result(int ready,List<Long> failedTenantIds){}
    public Result backfill(){int ready=0;var failed=new ArrayList<Long>();
        for(long tenant:jdbc.queryForList("SELECT t.tenant_id FROM tenants t JOIN users u ON u.user_id=t.owner_user_id JOIN tenant_memberships m ON m.tenant_id=t.tenant_id AND m.user_id=u.user_id AND m.status IN ('ACTIVE','SUSPENDED') WHERE t.tenant_type='INDIVIDUAL' AND t.status<>'DELETING' AND NOT EXISTS(SELECT 1 FROM user_deletion_jobs j WHERE j.tenant_id=t.tenant_id AND j.status<>'COMPLETE') ORDER BY t.tenant_id",Long.class)){
            try{storage.provision(tenant);ready++;}catch(RuntimeException failure){failed.add(tenant);}
        }return new Result(ready,List.copyOf(failed));
    }
    @Override public void run(ApplicationArguments arguments){if(!settings.getProperty("app.user.storage.backfill-individuals",Boolean.class,false))return;
        var result=backfill();org.slf4j.LoggerFactory.getLogger(IndividualStorageBackfill.class).info("INDIVIDUAL_STORAGE_BACKFILL ready={} failedTenantIds={}",result.ready(),result.failedTenantIds());
        if(!result.failedTenantIds().isEmpty())throw new IllegalStateException("Individual storage backfill failed. Correct host permissions or deletion locks, then retry.");}
}
