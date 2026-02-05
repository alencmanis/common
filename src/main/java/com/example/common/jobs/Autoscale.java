package com.example.common.jobs;


import com.example.common.config.AutoscaleProps;
import com.example.common.fly.MachinesManagerService;
import com.example.common.persistence.dao.AppRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public abstract class Autoscale {
    private static final Logger log = LoggerFactory.getLogger(Autoscale.class);

    private final MachinesManagerService machines;
    private final AutoscaleProps props;
    private final AppRepo appRepo;

    // English comment: In-memory cooldown; use Redis/Mongo if multiple replicas.
    private final AtomicLong lastScaleEpochMillis = new AtomicLong(0);

    // English comment: Prevent concurrent autoscale executions (single-flight).
    private final AtomicBoolean running = new AtomicBoolean(false);

    // English comment: Track how long backlog stayed at 0 to decide when to suspend non-master machines.
    private final AtomicLong lastZeroBacklogAt = new AtomicLong(0L);
    private final Duration suspendDelay = Duration.ofSeconds(300);

    public Autoscale(MachinesManagerService machines,
                     AutoscaleProps props,
                     AppRepo appRepo) {
        this.machines = machines;
        this.props = props;
        this.appRepo = appRepo;
    }

    public abstract Mono<Long> hasJob();

    public void tick() {
        if (!running.compareAndSet(false, true)) {
            log.info("autoscale: previous run still active, skipping tick");
            return;
        }

        try {
            if (!props.enabled()) {
                log.info("autoscale: is disabled, skipping tick");
                running.set(false);
                return;
            }

            final String selfId = System.getenv("FLY_MACHINE_ID");
            if (selfId == null || selfId.isBlank()) {
                log.warn("autoscale: FLY_MACHINE_ID is not set, skipping tick");
                running.set(false);
                return;
            }

            hasJob()
                    .flatMap(newCount -> {

                        // ---- 1) Suspend logic: ONLY for non-master AND only when backlog == 0 ----
                        if (newCount == 0) {
                            long now = System.currentTimeMillis();
                            lastZeroBacklogAt.compareAndSet(0L, now);

                            return appRepo.existsByMasterMachineId(selfId)
                                    .onErrorReturn(false)
                                    .flatMap(isMasterNow -> {
                                        if (Boolean.TRUE.equals(isMasterNow)) {
                                            // Master should never suspend itself.
                                            lastZeroBacklogAt.set(0L);
                                            return Mono.empty();
                                        }

                                        long zeroSince = lastZeroBacklogAt.get();
                                        long elapsed = now - zeroSince;

                                        if (elapsed < suspendDelay.toMillis()) {
                                            log.info("autoscale: backlog=0 and not master, waiting {}ms before suspend",
                                                    (suspendDelay.toMillis() - elapsed));
                                            return Mono.empty();
                                        }

                                        log.warn("autoscale: backlog=0 for {}ms and not master -> suspending self ({})",
                                                elapsed, selfId);

                                        lastZeroBacklogAt.set(0L);
                                        return machines.suspendMachine(selfId)
                                                .onErrorResume(e -> {
                                                    // If suspend failed, allow retry on future ticks.
                                                    lastZeroBacklogAt.compareAndSet(0L, System.currentTimeMillis());
                                                    return Mono.error(e);
                                                });
                                    });
                        } else {
                            // Backlog is not zero => reset the timer.
                            lastZeroBacklogAt.set(0L);
                        }

                        // ---- 2) Scale-up logic: ONLY master may scale ----
                        return appRepo.existsByMasterMachineId(selfId)
                                .onErrorReturn(false)
                                .flatMap(isMasterNow -> {
                                    if (!Boolean.TRUE.equals(isMasterNow)) {
                                        // Non-master does not scale.
                                        return Mono.empty();
                                    }

                                    if (newCount <= props.minBacklogToScale()) {
                                        return Mono.empty();
                                    }
                                    return machines.listManaged()
                                            .filter(m -> "started".equals(m.state()))
                                            .count()
                                            .flatMap(current -> {
                                                long target = Math.min(props.maxMachines(), current + props.scaleStep());
                                                long need = Math.max(0L, target - current);

                                                if (need == 0) {
                                                    return Mono.empty();
                                                }

                                                log.warn("autoscale: backlog={} > minBacklog={} -> scale {} -> {} (need={})",
                                                        newCount, props.minBacklogToScale(), current, target, need);

                                                return machines.listManaged()
                                                        .filter(m -> "stopped".equals(m.state()) || "suspended".equals(m.state()))
                                                        .take(need)
                                                        // English comment: Start sequentially to avoid Machines API rate limits
                                                        .concatMap(m -> machines.start(m.id())
                                                                .delayElement(Duration.ofMillis(250)))
                                                        .then();
                                            })
                                            // English comment: Update lastScale only if we actually executed the start pipeline
                                            .doOnSuccess(v -> lastScaleEpochMillis.set(System.currentTimeMillis()));

                                });
                    })
                    .onErrorResume(e -> {
                        log.error("autoscale: job failed {}", e.getMessage(), e);
                        return Mono.empty();
                    })
                    .doFinally(sig -> running.set(false))
                    .subscribe();

        } catch (Exception e) {
            running.set(false);
            throw e;
        }
    }
}
