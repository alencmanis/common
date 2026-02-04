package com.example.common.fly;

import com.example.common.fly.dto.CreateMachineRequest;
import com.example.common.fly.dto.Machine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class MachinesManagerService {

    private static final Logger log = LoggerFactory.getLogger(MachinesManagerService.class);

    private final FlyMachinesClient client;

    public MachinesManagerService(FlyMachinesClient client) {
        this.client = client;
    }

    public Flux<Machine> listManaged() {
        return client.listMachines();
    }

    public Mono<Void> start(String... statuses) {
        return client.listMachines()
                .collectList()
                .flatMap(list -> {
                    long matches = list.stream()
                            .filter(m -> Arrays.asList(statuses).contains(m.state()))
                            .count();

                    log.warn("start(status={}): total={} matches={}", Arrays.asList(statuses), list.size(), matches);

                    return Flux.fromIterable(list)
                            .filter(m -> Arrays.asList(statuses).contains(m.state()))
                            .flatMap(m ->
                                    client.startMachine(m.id())
                                            .doOnSubscribe(s -> log.info("startMachine {}", m.id()))
                                            .onErrorResume(e -> {
                                                log.error("startMachine failed id={}", m.id(), e);
                                                return Mono.empty();
                                            })
                            )
                            .then();
                });
    }


    public Mono<ScaleResult> scaleTo(String role, int targetCount) {
        if (targetCount < 0) {
            return Mono.error(new IllegalArgumentException("targetCount must be >= 0"));
        }

        return listManaged()
                .collectList()
                .flatMap(currentList -> {
                    int current = currentList.size();

                    if (current == targetCount) {
                        return Mono.just(new ScaleResult(current, 0, 0));
                    }

                    if (current < targetCount) {
                        int need = targetCount - current;

                        // English comment: First try to start any stopped managed machines (if you keep stopped around).
                        Mono<Integer> startedCountMono = Flux.fromIterable(currentList)
                                .filter(m -> isStopped(m.state()))
                                .take(need)
                                .flatMap(m -> client.startMachine(m.id())
                                        .onErrorResume(e -> Mono.empty()), 2)
                                .count()
                                .map(Long::intValue);

                        return startedCountMono.flatMap(startedCount -> {
                            int remaining = need - startedCount;
                            if (remaining <= 0) {
                                return Mono.just(new ScaleResult(current + startedCount, 0, 0));
                            }

                            return pickBaseMachineForClone()
                                    .flatMap(base -> Flux.range(0, remaining)
                                            .flatMap(i -> createClone(role, base), 2)
                                            .collectList()
                                            .map(created -> new ScaleResult(
                                                    current + startedCount + created.size(),
                                                    created.size(),
                                                    0
                                            )));
                        });
                    }

                    // current > targetCount
                    int toDelete = current - targetCount;

                    // English comment: Delete only managed machines; pick a deterministic subset.
                    return Flux.fromIterable(pickVictimsForDelete(currentList, toDelete))
                            .flatMap(m -> client.destroyMachine(m.id())
                                    .onErrorResume(e -> Mono.empty()), 2)
                            .count()
                            .map(Long::intValue)
                            .map(deleted -> new ScaleResult(current - deleted, 0, deleted));
                });
    }

    private boolean isStopped(String state) {
        if (state == null) return false;
        String s = state.toLowerCase();
        return s.equals("stopped") || s.equals("created");
    }

    private java.util.List<Machine> pickVictimsForDelete(java.util.List<Machine> currentList, int toDelete) {
        // English comment: Prefer deleting stopped machines first; otherwise delete arbitrary (but deterministic by id).
        return currentList.stream()
                .sorted((a, b) -> {
                    int sa = isStopped(a.state()) ? 0 : 1;
                    int sb = isStopped(b.state()) ? 0 : 1;
                    if (sa != sb) return Integer.compare(sa, sb);
                    String ida = a.id() == null ? "" : a.id();
                    String idb = b.id() == null ? "" : b.id();
                    return ida.compareTo(idb);
                })
                .limit(Math.max(0, toDelete))
                .toList();
    }


    /**
     * Picks a base machine to clone config from (prefer started/running, otherwise any).
     */
    public Mono<Machine> pickBaseMachineForClone() {
        return client.listMachines()
                .filter(m -> m.config() != null)
                .sort((a, b) -> scoreState(b.state()) - scoreState(a.state()))
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException("No machines found to clone config from")));
    }

    public Mono<Machine> createClone(String role, Machine base) {
        Map<String, Object> cfg = base.config();
        if (cfg == null || cfg.isEmpty()) {
            return Mono.error(new IllegalArgumentException("Base machine has no config"));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("managedBy", "mqprocessor");
        meta.put("role", role);
        meta.put("clonedFrom", base.id());

        String name = role + "-" + UUID.randomUUID().toString().substring(0, 8);

        Object image = cfg.get("image");
        log.warn("autoscale: creating clone name={} region={} image={}", name, base.region(), image);

        CreateMachineRequest req = new CreateMachineRequest(
                name,
                base.region(),
                cfg,       // <-- raw config, no DTO transforms
                false,
                meta
        );

        return client.createMachine(req);
    }

    private static boolean hasMeta(Machine m, String k, String v) {
        if (m == null || m.metadata() == null) return false;
        Object val = m.metadata().get(k);
        return val != null && v.equalsIgnoreCase(String.valueOf(val));
    }

    private static int scoreState(String state) {
        if (state == null) return 0;
        String s = state.toLowerCase();
        if (s.equals("started") || s.equals("running")) return 3;
        if (s.equals("starting")) return 2;
        if (s.equals("stopped")) return 1;
        return 0;
    }

    public record ScaleResult(int finalCount, int created, int deleted) {
    }
}
