package com.example.common.persistence.fly;


import com.example.common.persistence.fly.dto.CreateMachineRequest;
import com.example.common.persistence.fly.dto.Machine;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class FlyMachinesClient {

    private final WebClient wc;

    public FlyMachinesClient(WebClient flyWebClient) {
        this.wc = flyWebClient;
    }

    private Duration timeout() {
        return Duration.ofMillis(15000);
    }

    public Flux<Machine> listMachines() {
        return wc.get()
                .uri("/v1/apps/{app}/machines", System.getenv("FLY_APP_NAME"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToFlux(Machine.class)
                .timeout(timeout());
    }

    public Mono<Machine> getMachine(String machineId) {
        return wc.get()
                .uri("/v1/apps/{app}/machines/{id}", System.getenv("FLY_APP_NAME"), machineId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToMono(Machine.class)
                .timeout(timeout());
    }

    public Mono<Machine> createMachine(CreateMachineRequest req) {
        return wc.post()
                .uri("/v1/apps/{app}/machines", System.getenv("FLY_APP_NAME"))
                .bodyValue(req)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToMono(Machine.class)
                .timeout(timeout());
    }

    public Mono<Void> startMachine(String machineId) {
        return actionPostVoid("/v1/apps/{app}/machines/{id}/start", machineId);
    }

    public Mono<Void> stopMachine(String machineId) {
        return actionPostVoid("/v1/apps/{app}/machines/{id}/stop", machineId);
    }

    public Mono<Void> restartMachine(String machineId) {
        return actionPostVoid("/v1/apps/{app}/machines/{id}/restart", machineId);
    }

    public Mono<Void> destroyMachine(String machineId) {
        return wc.delete()
                .uri("/v1/apps/{app}/machines/{id}", System.getenv("FLY_APP_NAME"), machineId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToMono(Void.class)
                .timeout(timeout());
    }

    private Mono<Void> actionPostVoid(String pathTemplate, String machineId) {
        return wc.post()
                .uri(pathTemplate, System.getenv("FLY_APP_NAME"), machineId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToMono(Void.class)
                .timeout(timeout());
    }

    private Mono<? extends Throwable> toFlyError(ClientResponse resp) {
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(new com.example.common.persistence.fly.FlyApiException(resp.statusCode().value(), body)));
    }
}
