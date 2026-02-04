package com.example.common.fly;

import com.example.fly.dto.CreateMachineRequest;
import com.example.fly.dto.Machine;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class MachinesService {

    private final FlyMachinesClient client;

    public MachinesService(FlyMachinesClient client) {
        this.client = client;
    }

    public Mono<Machine> createAndStart(CreateMachineRequest req) {
        return client.createMachine(req)
                .flatMap(m -> client.startMachine(m.id()).thenReturn(m))
                .flatMap(m -> waitForState(m.id(), "started", Duration.ofSeconds(60)).thenReturn(m));
    }

    public Mono<Void> stopAndWait(String machineId) {
        return client.stopMachine(machineId)
                .then(waitForState(machineId, "stopped", Duration.ofSeconds(60)));
    }

    public Mono<Void> waitForState(String machineId, String desiredState, Duration maxWait) {
        // English comment: Poll machine state with fixed interval until desiredState or timeout.
        Duration poll = Duration.ofMillis(700);

        return Flux.interval(Duration.ZERO, poll)
                .flatMap(tick -> client.getMachine(machineId))
                .filter(m -> desiredState.equalsIgnoreCase(m.state()))
                .next()
                .timeout(maxWait)
                .then();
    }

    public Mono<Void> safeStart(String machineId) {
        return client.getMachine(machineId)
                .flatMap(m -> {
                    String s = m.state() == null ? "" : m.state().toLowerCase();
                    if (s.equals("started") || s.equals("running")) {
                        return Mono.empty();
                    }
                    if (s.equals("starting")) {
                        return waitForState(machineId, "started", Duration.ofSeconds(60));
                    }
                    return client.startMachine(machineId)
                            .onErrorResume(FlyApiException.class, e -> {
                                // English comment: Lease contention - retry after a short delay.
                                if (looksLikeLeaseConflict(e.getMessage())) {
                                    return Mono.delay(Duration.ofSeconds(2))
                                            .then(safeStart(machineId));
                                }
                                return Mono.error(e);
                            })
                            .then(waitForState(machineId, "started", Duration.ofSeconds(60)));
                });
    }

    private boolean looksLikeLeaseConflict(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("lease currently held") || m.contains("lease") && m.contains("expires");
    }

}
