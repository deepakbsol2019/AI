package com.book.user.service;

import java.sql.Timestamp;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.book.storage.TenantStoragePaths;
import com.book.user.security.UserPrincipal;
import com.book.user.service.UserManagementService.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import static com.book.user.service.UserManagementService.*;

/** Durable hard-deletion state machine: snapshot/block -> database purge -> exact filesystem cleanup -> complete. */
@Service
public class UserDeletionService {
    final JdbcTemplate jdbc;final UserManagementService users;final UserDeletionFiles files;final TenantStoragePaths storage;
    final TransactionTemplate tx;final Clock clock;final JsonMapper json=JsonMapper.builder().build();
    final AuthorizationService authorization;
    public UserDeletionService(JdbcTemplate jdbc,UserManagementService users,UserDeletionFiles files,TenantStoragePaths storage,PlatformTransactionManager manager,Clock clock,AuthorizationService authorization){
        this.jdbc=jdbc;this.users=users;this.files=files;this.storage=storage;this.clock=clock;this.authorization=authorization;tx=new TransactionTemplate(manager);
    }
    public record Plan(List<Long> userIds,List<Long> bookIds,List<Long> authorIds,String expectedScope,UserDeletionFiles.Manifest files){}
    public record Job(long jobId,long tenantId,long userId,String scope,String status,boolean databasePurged,int attempts,String error,Instant updatedAt){}
    @Transactional
    public Job request(UserPrincipal actor,long userId,Confirm input){
        var target=users.lock(actor,userId,"USER_HARD_DELETE");users.protect(actor,target);var preview=users.preview(actor,target,true);users.confirm(preview,input);
        long tenant=preview.tenantId();String key=UUID.randomUUID().toString();boolean purgeTenant=preview.scope().equals("TENANT_PURGE");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status WHERE tenant_id=? AND status='inprogress'",Long.class,tenant)>0)throw conflict("Wait for tenant uploads to finish before deletion.");
        List<Long> userIds=purgeTenant?users.tenantUsers(tenant):List.of(userId);
        if(purgeTenant && target.get("owner_user_id")!=null && !userIds.contains(id(target,"owner_user_id")))throw conflict("Tenant ownership is ambiguous. Operator review is required.");
        for(long id:userIds)if(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE owner_user_id=? AND tenant_id<>?",Long.class,id,tenant)>0)throw conflict("Account owns another tenant. Resolve ownership before deletion.");
        List<Long> books=purgeTenant?jdbc.queryForList("SELECT book_id FROM pdf_file_details WHERE tenant_id=?",Long.class,tenant):List.of();
        List<Long> authors=purgeTenant?jdbc.queryForList("SELECT DISTINCT author_id FROM pdf_file_details WHERE tenant_id=?",Long.class,tenant):List.of();
        UserDeletionFiles.Manifest manifest=new UserDeletionFiles.Manifest(storage.tenantPath(tenant).toString(),List.of());
        try {
            if(purgeTenant){
                storage.lockDeletion(tenant,key);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{storage.unlockDeletion(tenant,key);}catch(Exception failure){org.slf4j.LoggerFactory.getLogger(UserDeletionService.class).error("DELETION_LOCK_ROLLBACK_REQUIRES_REVIEW");}}});
                manifest=files.snapshot(tenant);
                for(String path:jdbc.queryForList("SELECT t.pdf_path FROM pdf_file_topic_details t JOIN pdf_file_details b ON b.book_id=t.book_id WHERE b.tenant_id=? AND t.pdf_path IS NOT NULL",String.class,tenant)){
                    Path supplied=Path.of(path);Path candidate=(supplied.isAbsolute()?supplied:storage.tenantPath(tenant).resolve(supplied)).normalize();
                    if(!candidate.startsWith(storage.tenantPath(tenant)))throw conflict("Legacy shared paths require an ownership-reviewed migration before tenant deletion.");
                }
                users.assertNoHighestInTenant(tenant);
            } else if(preview.scope().equals("ACCOUNT_PURGE")){
                var inventory=files.snapshot(tenant);
                if(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_details WHERE tenant_id=?",Long.class,tenant)>0 || jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status WHERE tenant_id=?",Long.class,tenant)>0 || inventory.entries().stream().anyMatch(e->!e.relative().isEmpty()))
                    throw conflict("This company stores files/content without individual uploader ownership. Super-user employee cleanup is ambiguous and is blocked; shared tenant data will not be guessed or deleted.");
            }
        }catch(java.io.IOException failure){throw conflict("Storage could not be validated for deletion. No deletion job was started.");}
        String data=json.writeValueAsString(new Plan(userIds,books,authors,preview.scope(),manifest));var holder=new GeneratedKeyHolder();
        jdbc.update(connection->{var s=connection.prepareStatement("INSERT INTO user_deletion_jobs(job_key,tenant_id,target_user_id,actor_user_id,deletion_scope,status,manifest,db_purged,attempts,created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',?,FALSE,0,?,?)",new String[]{"job_id"});
            Object[] values={key,tenant,userId,actor.userId(),preview.scope(),data,now(),now()};for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s;},holder);
        for(long id:userIds){users.revoke(id);jdbc.update("UPDATE users SET status='DELETING',security_version=security_version+1,management_version=management_version+1,updated_at=? WHERE user_id=?",now(),id);}
        if(purgeTenant)jdbc.update("UPDATE tenants SET status='DELETING',authz_version=authz_version+1,updated_at=? WHERE tenant_id=?",now(),tenant);
        users.audit(actor,tenant,userId,"USER_HARD_DELETE_REQUEST",Map.of("jobId",holder.getKey(),"scope",preview.scope(),"affectedUserIds",userIds));
        return job(((Number)holder.getKey()).longValue());
    }
    @Transactional(readOnly=true)
    public Job get(UserPrincipal actor,long jobId){users.authorize(actor,"USER_VIEW");var job=job(jobId);if(!authorization.isSuperUser(actor) && (job.tenantId()!=actor.tenantId() || !job.scope().equals("ACCOUNT_PRESERVE")))throw missing();return job;}
    @Transactional(readOnly=true)
    public List<Job> list(UserPrincipal actor){users.authorize(actor,"USER_VIEW");return jdbc.queryForList("SELECT job_id FROM user_deletion_jobs WHERE (?=TRUE OR (tenant_id=? AND deletion_scope='ACCOUNT_PRESERVE')) ORDER BY job_id DESC LIMIT 100",Long.class,authorization.isSuperUser(actor),actor.tenantId()).stream().map(this::job).toList();}
    @Transactional
    public Job retry(UserPrincipal actor,long jobId){users.authorize(actor,"USER_HARD_DELETE");users.requireSuper(actor);var job=job(jobId);
        if(!job.status().equals("FAILED"))throw conflict("Only failed jobs can be retried.");
        jdbc.update("UPDATE user_deletion_jobs SET actor_user_id=?,status=CASE WHEN db_purged=TRUE THEN 'CLEANUP_PENDING' ELSE 'PENDING' END,last_error=NULL,lease_until=NULL,attempts=0,updated_at=? WHERE job_id=? AND status='FAILED'",actor.userId(),now(),jobId);
        // Audit is retained independently even after the target tenant has been physically removed.
        jdbc.update("INSERT INTO audit_events(actor_user_id,event_code,target_type,target_id,outcome,details,occurred_at) VALUES(?,'USER_DELETE_RETRY','DELETION_JOB',?,'SUCCESS',?,?)",actor.userId(),Long.toString(jobId),json.writeValueAsString(Map.of("tenantId",job.tenantId(),"targetUserId",job.userId())),now());return job(jobId);}
    public void processAvailable(){
        for(long id:jdbc.queryForList("SELECT job_id FROM user_deletion_jobs WHERE status IN ('PENDING','CLEANUP_PENDING') AND (lease_until IS NULL OR lease_until<?) ORDER BY job_id LIMIT 10",Long.class,now()))process(id);
    }
    public void process(long jobId){
        Boolean claimed=tx.execute(s->jdbc.update("UPDATE user_deletion_jobs SET lease_until=?,attempts=attempts+1,updated_at=? WHERE job_id=? AND status IN ('PENDING','CLEANUP_PENDING') AND (lease_until IS NULL OR lease_until<?)",Timestamp.from(clock.instant().plusSeconds(300)),now(),jobId,now())==1);
        if(!Boolean.TRUE.equals(claimed))return;
        try{
            tx.executeWithoutResult(s->{var rows=jdbc.queryForList("SELECT * FROM user_deletion_jobs WHERE job_id=? FOR UPDATE",jobId);var row=rows.getFirst();
                if(!Boolean.TRUE.equals(row.get("db_purged"))){
                    long tenant=id(row,"tenant_id");jdbc.queryForList("SELECT tenant_id FROM tenants WHERE tenant_id=? FOR UPDATE",tenant);
                    var actor=users.principal(users.row(id(row,"actor_user_id")));users.authorize(actor,"USER_HARD_DELETE");
                    Plan plan=json.readValue(str(row,"manifest"),Plan.class);boolean all=str(row,"deletion_scope").equals("TENANT_PURGE");
                    if(!str(row,"deletion_scope").equals("ACCOUNT_PRESERVE"))users.requireSuper(actor);
                    var target=users.row(id(row,"target_user_id"));users.scope(actor,target);users.protect(actor,target);
                    if(!all && users.admin(target))throw conflict("Target became a protected admin; deletion requires a new review.");
                    if(all){users.assertNoHighestInTenant(tenant);if(!new TreeSet<>(users.tenantUsers(tenant)).equals(new TreeSet<>(plan.userIds())))throw conflict("Tenant membership changed; deletion needs review.");}
                    if(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status WHERE tenant_id=? AND status='inprogress'",Long.class,tenant)>0)throw conflict("Tenant upload is still active.");
                    if(all && !new TreeSet<>(jdbc.queryForList("SELECT book_id FROM pdf_file_details WHERE tenant_id=?",Long.class,tenant)).equals(new TreeSet<>(plan.bookIds())))throw conflict("Tenant content changed; deletion requires review.");
                    if(str(row,"deletion_scope").equals("ACCOUNT_PURGE"))try{
                        if(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_details WHERE tenant_id=?",Long.class,tenant)>0 || jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status WHERE tenant_id=?",Long.class,tenant)>0 || files.snapshot(tenant).entries().stream().anyMatch(e->!e.relative().isEmpty()))throw conflict("Employee content ownership became ambiguous.");
                    }catch(java.io.IOException failure){throw conflict("Storage ownership could not be revalidated.");}
                    purge(row,plan,actor);
                    jdbc.update("UPDATE user_deletion_jobs SET db_purged=TRUE,status='CLEANUP_PENDING',updated_at=? WHERE job_id=?",now(),jobId);
                }
            });
            tx.executeWithoutResult(s->{
                var row=jdbc.queryForMap("SELECT * FROM user_deletion_jobs WHERE job_id=? FOR UPDATE",jobId);
                if(str(row,"status").equals("COMPLETE"))return;
                var cleanupActor=users.principal(users.row(id(row,"actor_user_id")));
                jdbc.queryForList("SELECT tenant_id FROM tenants WHERE tenant_id=? FOR UPDATE",cleanupActor.tenantId());
                users.authorize(cleanupActor,"USER_HARD_DELETE");
                if(!str(row,"deletion_scope").equals("ACCOUNT_PRESERVE"))users.requireSuper(cleanupActor);
                else if(cleanupActor.tenantId()!=id(row,"tenant_id") && !authorization.isSuperUser(cleanupActor))throw denied();
                Plan plan=json.readValue(str(row,"manifest"),Plan.class);
                try {if(str(row,"deletion_scope").equals("TENANT_PURGE"))files.delete(id(row,"tenant_id"),plan.files());}
                catch(java.io.IOException failure){throw new IllegalStateException("Cleanup requires review");}
                jdbc.update("UPDATE user_deletion_jobs SET status='COMPLETE',last_error=NULL,lease_until=NULL,updated_at=? WHERE job_id=?",now(),jobId);
                jdbc.update("INSERT INTO audit_events(actor_user_id,event_code,target_type,target_id,outcome,details,occurred_at) VALUES(?,'USER_HARD_DELETE_COMPLETE','DELETION_JOB',?,'SUCCESS',?,?)",id(row,"actor_user_id"),Long.toString(jobId),json.writeValueAsString(Map.of("tenantId",id(row,"tenant_id"),"targetUserId",id(row,"target_user_id"),"scope",str(row,"deletion_scope"))),now());});
        }catch(Exception failure){
            tx.executeWithoutResult(s->jdbc.update("UPDATE user_deletion_jobs SET status='FAILED',lease_until=NULL,last_error='Deletion could not complete. Ownership, authorization or filesystem cleanup requires review; retry safely after correction.',updated_at=? WHERE job_id=? AND status<>'COMPLETE'",now(),jobId));
            org.slf4j.LoggerFactory.getLogger(UserDeletionService.class).warn("USER_DELETION_JOB_FAILED jobId={} failureType={}",jobId,failure.getClass().getSimpleName());
        }
    }
    private void purge(Map<String,Object> job,Plan plan,UserPrincipal actor){
        long tenant=id(job,"tenant_id");boolean all=str(job,"deletion_scope").equals("TENANT_PURGE");
        if(all){
            // Provider references to accounts in other organizations must never be swept away.
            for(long provider:jdbc.queryForList("SELECT identity_provider_id FROM identity_providers WHERE tenant_id=?",Long.class,tenant)){
                for(long foreign:jdbc.queryForList("SELECT user_id FROM external_identities WHERE identity_provider_id=?",Long.class,provider))if(!plan.userIds().contains(foreign))throw conflict("Provider has foreign account references.");
                for(String table:List.of("credential_tokens","auth_sessions"))for(long foreign:jdbc.queryForList("SELECT user_id FROM "+table+" WHERE identity_provider_id=?",Long.class,provider))if(!plan.userIds().contains(foreign))throw conflict("Provider has foreign authentication references.");
            }
            jdbc.update("DELETE FROM pdf_file_topic_details WHERE book_id IN(SELECT book_id FROM pdf_file_details WHERE tenant_id=?)",tenant);
            jdbc.update("DELETE FROM pdf_file_details WHERE tenant_id=?",tenant);
            // The additive spreadsheet migration may not exist on older installations yet.
            Boolean spreadsheets=jdbc.execute((java.sql.Connection connection)->{
                try(var tables=connection.getMetaData().getTables(null,null,"spreadsheet_file_details",new String[]{"TABLE"})){return tables.next();}
            });
            if(Boolean.TRUE.equals(spreadsheets))jdbc.update("DELETE FROM spreadsheet_file_details WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM pdf_file_status WHERE tenant_id=?",tenant);
            for(long author:plan.authorIds())jdbc.update("DELETE FROM pdf_file_author_details WHERE aouthor_id=? AND NOT EXISTS(SELECT 1 FROM pdf_file_details WHERE author_id=?)",author,author);
        }
        for(long user:plan.userIds())purgeAccount(user);
        if(all){
            // Purge historical memberships/session associations of this tenant, without deleting users now elsewhere.
            clearSessions("tenant_id",tenant);
            jdbc.update("DELETE FROM credential_tokens WHERE identity_provider_id IN(SELECT identity_provider_id FROM identity_providers WHERE tenant_id=?)",tenant);
            jdbc.update("DELETE FROM external_identities WHERE identity_provider_id IN(SELECT identity_provider_id FROM identity_providers WHERE tenant_id=?)",tenant);
            jdbc.update("DELETE FROM identity_providers WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM user_roles WHERE tenant_id=?",tenant);jdbc.update("DELETE FROM role_permissions WHERE tenant_id=?",tenant);jdbc.update("DELETE FROM roles WHERE tenant_id=?",tenant);
            jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id=?",tenant);
            retainAudit(null,tenant);jdbc.update("DELETE FROM tenants WHERE tenant_id=?",tenant);
        }
    }
    private void purgeAccount(long user){
        clearSessions("user_id",user);
        jdbc.update("DELETE FROM credential_tokens WHERE user_id=?",user);jdbc.update("DELETE FROM recovery_codes WHERE user_id=?",user);jdbc.update("DELETE FROM local_mfa_factors WHERE user_id=?",user);
        jdbc.update("DELETE FROM local_credentials WHERE user_id=?",user);jdbc.update("DELETE FROM external_identities WHERE user_id=?",user);
        jdbc.update("UPDATE user_roles SET granted_by_membership_id=NULL WHERE granted_by_membership_id IN(SELECT membership_id FROM tenant_memberships WHERE user_id=?)",user);
        jdbc.update("DELETE FROM user_roles WHERE membership_id IN(SELECT membership_id FROM tenant_memberships WHERE user_id=?)",user);
        jdbc.update("DELETE FROM tenant_memberships WHERE user_id=?",user);jdbc.update("UPDATE tenants SET owner_user_id=NULL WHERE owner_user_id=?",user);
        retainAudit(user,null);jdbc.update("DELETE FROM users WHERE user_id=?",user);
    }
    private void clearSessions(String column,long value){
        jdbc.update("UPDATE refresh_tokens SET parent_refresh_token_id=NULL WHERE session_id IN(SELECT session_id FROM auth_sessions WHERE "+column+"=?)",value);
        jdbc.update("DELETE FROM refresh_tokens WHERE session_id IN(SELECT session_id FROM auth_sessions WHERE "+column+"=?)",value);jdbc.update("DELETE FROM auth_sessions WHERE "+column+"=?",value);
    }
    private void retainAudit(Long user,Long tenant){
        var records=jdbc.queryForList("SELECT audit_event_id,details FROM audit_events WHERE "+(user!=null?"actor_user_id":"tenant_id")+"=?",user!=null?user:tenant);
        for(var row:records){var details=new LinkedHashMap<String,Object>();details.put(user!=null?"retainedActorUserId":"retainedTenantId",user!=null?user:tenant);if(row.get("details")!=null){Object prior=row.get("details");String text=prior instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):prior.toString();try{details.put("priorDetails",json.readTree(text));}catch(RuntimeException invalid){details.put("priorDetails",text);}}
            jdbc.update("UPDATE audit_events SET "+(user!=null?"actor_user_id":"tenant_id")+"=NULL,details=? WHERE audit_event_id=?",json.writeValueAsString(details),id(row,"audit_event_id"));}
    }
    private Job job(long id){var rows=jdbc.queryForList("SELECT * FROM user_deletion_jobs WHERE job_id=?",id);if(rows.size()!=1)throw missing();var row=rows.getFirst();return new Job(id,id(row,"tenant_id"),id(row,"target_user_id"),str(row,"deletion_scope"),str(row,"status"),Boolean.TRUE.equals(row.get("db_purged")),((Number)row.get("attempts")).intValue(),str(row,"last_error"),instant(row,"updated_at"));}
    private Timestamp now(){return Timestamp.from(clock.instant());}
}
