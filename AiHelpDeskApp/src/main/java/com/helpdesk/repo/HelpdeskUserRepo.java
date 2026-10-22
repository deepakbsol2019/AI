package com.helpdesk.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.helpdesk.enitity.DepartmentNames;
import com.helpdesk.enitity.HelpdeskUsers;
import com.helpdesk.enitity.TicketStatus;

public interface HelpdeskUserRepo extends JpaRepository<HelpdeskUsers,Long>{
	Optional<HelpdeskUsers> findByUsername(String username);

    @Query(value = """
        SELECT u.*
        FROM helpdesk_user u
        LEFT JOIN ticket_dtls t
            ON t.assigned_to_user_id = u.user_id
        WHERE u.user_dept = 'SUPPORT'
        GROUP BY u.user_id
        ORDER BY
            SUM(CASE WHEN t.ticket_status = 'OPEN'
                     THEN 1 ELSE 0 END) ASC,

            SUM(CASE WHEN t.ticket_status = 'INPROGRESS'
                     THEN 1 ELSE 0 END) ASC,

            COUNT(t.ticket_id) DESC,

            u.user_id ASC
        LIMIT 1
        """, nativeQuery = true)
    Optional<HelpdeskUsers> findBestSupportUser();
}
