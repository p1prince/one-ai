package com.oneai.app.core

enum class ServiceState { REGISTERED, RUNNING, STOPPED, FAILED }

/** A core-owned unit with a start/stop lifecycle. Implementations must not depend on any plugin. */
interface CoreService {
    val id: String
    fun start()
    fun stop()
}

/**
 * Registers and runs core services with failure isolation: one service failing to start or
 * stop never prevents the others from doing so, and every failure is reported, not swallowed.
 */
class CoreServiceManager(private val report: (CoreError) -> Unit = {}) {
    private val services = linkedMapOf<String, CoreService>()
    private val states = linkedMapOf<String, ServiceState>()

    @Synchronized
    fun register(service: CoreService): Result<Unit> {
        if (service.id.isBlank()) return Result.failure(IllegalArgumentException("Service ID is required"))
        if (service.id in services) return Result.failure(IllegalStateException("Service already registered: ${service.id}"))
        services[service.id] = service
        states[service.id] = ServiceState.REGISTERED
        return Result.success(Unit)
    }

    @Synchronized
    fun start(id: String): Result<Unit> {
        val service = services[id] ?: return Result.failure(IllegalArgumentException("Unknown service: $id"))
        if (states[id] == ServiceState.RUNNING) return Result.success(Unit)
        return try {
            service.start()
            states[id] = ServiceState.RUNNING
            Result.success(Unit)
        } catch (e: Exception) {
            states[id] = ServiceState.FAILED
            report(CoreErrors.service("SERVICE_START_FAILED", "Service '$id' could not start."))
            Result.failure(e)
        }
    }

    @Synchronized
    fun stop(id: String): Result<Unit> {
        val service = services[id] ?: return Result.failure(IllegalArgumentException("Unknown service: $id"))
        if (states[id] != ServiceState.RUNNING) return Result.success(Unit)
        return try {
            service.stop()
            states[id] = ServiceState.STOPPED
            Result.success(Unit)
        } catch (e: Exception) {
            states[id] = ServiceState.FAILED
            report(CoreErrors.service("SERVICE_STOP_FAILED", "Service '$id' did not stop cleanly."))
            Result.failure(e)
        }
    }

    /** Starts every service in registration order; returns each service's success. */
    @Synchronized
    fun startAll(): Map<String, Boolean> = services.keys.toList().associateWith { start(it).isSuccess }

    /** Stops running services in reverse registration order. */
    @Synchronized
    fun stopAll(): Map<String, Boolean> = services.keys.toList().asReversed().associateWith { stop(it).isSuccess }

    @Synchronized
    fun state(id: String): ServiceState? = states[id]

    @Synchronized
    fun states(): Map<String, ServiceState> = states.toMap()
}
