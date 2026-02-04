package com.example.common.persistence.jobs;


import com.example.common.persistence.entity.AppDoc;
import com.example.common.persistence.fly.FlyMachinesClient;
import com.example.common.persistence.fly.dto.Machine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
public class SelectMaster {
    private static final Logger log = LoggerFactory.getLogger(SelectMaster.class);
    private final ReactiveMongoTemplate mongo;
    private final FlyMachinesClient client;
    // Prevent overlapping scheduled runs on the same machine
    private final AtomicBoolean running = new AtomicBoolean(false);
    // Best-effort local suppression to avoid repeated "MASTER CHANGED" logs
    private volatile String lastLoggedMasterId = null;

    public SelectMaster(ReactiveMongoTemplate mongo, FlyMachinesClient client) {
        this.mongo = mongo;
        this.client = client;
    }

    @Scheduled(fixedDelayString = "${fly.master.job-delay-ms:300000}")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        ensureMaster()
                .doFinally(sig -> running.set(false))
                .subscribe(
                        v -> {
                        },
                        e -> log.error("select master failed", e)
                );
    }

    /**
     * Leader ideology:
     * - Master extends masterLeaseUntil into the future (leaseSeconds)
     * - If lease is missing/expired OR master is not STARTED -> any STARTED self may claim master
     * - Master renews lease quietly each run
     * - No findOne; only CAS in Mongo
     */
    public Mono<Void> ensureMaster() {
        return client.listMachines()
                .collectList()
                .flatMap(all -> {
                    String selfId = System.getenv("FLY_MACHINE_ID");

                    Machine self = all.stream()
                            .filter(m -> selfId.equals(m.id()))
                            .findFirst()
                            .orElse(null);

                    if (self == null) {
                        log.warn("Self machine not found in listMachines, selfId={}", selfId);
                        return Mono.empty();
                    }

                    if (!isEligibleMaster(self)) {
                        log.warn("Self not eligible: id={}, state={}", self.id(), self.state());
                        return Mono.empty();
                    }

                    Set<String> eligibleIds = all.stream()
                            .filter(this::isEligibleMaster)
                            .map(Machine::id)
                            .collect(Collectors.toSet());

                    return tryClaimMasterIfNeeded(selfId, eligibleIds)
                            .then(renewLeaseIfMaster(selfId))
                            .then();
                });

    }

    private boolean isEligibleMaster(Machine m) {
        return m != null && "started".equalsIgnoreCase(m.state());
    }


    /**
     * Claim master ONLY if current master is invalid:
     * - missing/null
     * - not in started set
     * - lease missing/null
     * - lease expired
     * <p>
     * Uses findAndModify (CAS). Logs only on real master change.
     */
    private Mono<Void> tryClaimMasterIfNeeded(String selfId, Set<String> startedIds) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(Duration.ofSeconds(Math.max(1, 360)));

        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("appName").is(System.getenv("FLY_APP_NAME")),
                new Criteria().orOperator(
                        Criteria.where("masterMachineId").exists(false),
                        Criteria.where("masterMachineId").is(null),
                        Criteria.where("masterMachineId").nin(startedIds),
                        Criteria.where("masterLeaseUntil").exists(false),
                        Criteria.where("masterLeaseUntil").is(null),
                        Criteria.where("masterLeaseUntil").lte(now)
                )
        ));

        Update update = new Update()
                .set("appName", System.getenv("FLY_APP_NAME"))
                .set("masterMachineId", selfId)
                .set("masterLeaseUntil", leaseUntil)
                .set("updatedAt", now);

        // Return OLD document so we can detect whether master actually changed.
        FindAndModifyOptions opts = FindAndModifyOptions.options()
                .upsert(true)
                .returnNew(false);

        return mongo.findAndModify(query, update, opts, AppDoc.class)
                .doOnNext(oldDoc -> {
                    String oldMaster = oldDoc != null ? oldDoc.masterMachineId() : null;
                    if (oldMaster == null || !oldMaster.equals(selfId)) {
                        logMasterChange(oldMaster, selfId, leaseUntil);
                    }
                })
                .switchIfEmpty(Mono.empty())
                .onErrorResume(DuplicateKeyException.class, e -> Mono.empty())
                .then();
    }

    /**
     * Renew lease ONLY if Mongo currently says masterMachineId == selfId.
     * Uses updateFirst (lighter than findAndModify) and stays quiet.
     */
    private Mono<Void> renewLeaseIfMaster(String selfId) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(Duration.ofSeconds(Math.max(1, 360)));

        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("appName").is(System.getenv("FLY_APP_NAME")),
                Criteria.where("masterMachineId").is(selfId)
        ));

        Update update = new Update()
                .set("masterLeaseUntil", leaseUntil)
                .set("updatedAt", now);

        return mongo.updateFirst(query, update, AppDoc.class).then();
    }

    private void logMasterChange(String oldMaster, String newMaster, Instant leaseUntil) {
        // Avoid spamming logs if we keep claiming the same master repeatedly.
        if (newMaster != null && newMaster.equals(lastLoggedMasterId)) {
            return;
        }
        lastLoggedMasterId = newMaster;

        log.info("MASTER CHANGED: {} -> {} (leaseUntil={})",
                oldMaster == null ? "<none>" : oldMaster,
                newMaster,
                leaseUntil
        );
    }
}
