package com.hkdzagent.agent.audit;

import java.util.List;

public interface AdminAuditRepository {

    AdminAuditEvent save(AdminAuditEvent event);

    List<AdminAuditEvent> findRecent(int limit);
}
