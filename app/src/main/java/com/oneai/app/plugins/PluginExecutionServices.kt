package com.oneai.app.plugins

import com.oneai.app.core.PluginStore
import com.oneai.app.core.PluginActionExecutor
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.PluginActionApprovalGateway
import com.oneai.app.security.PluginActionGateway

/** One process-scoped owner for execution and one-use approvals; never owned by persistence. */
class PluginExecutionServices(store: PluginStore) {
    val approvalGateway: PluginActionApprovalGateway = PluginActionGateway(
        findPlugin = { id -> store.plugins().firstOrNull { it.id == id } },
        audit = ActionAuditLogger { message -> store.log(message) }
    )
    val runtime = PluginRuntime(
        findInstalledPlugin = { id -> store.plugins().firstOrNull { it.id == id } },
        actionGateway = approvalGateway
    )
    val executor: PluginActionExecutor get() = runtime
}
