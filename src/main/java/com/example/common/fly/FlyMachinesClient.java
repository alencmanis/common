package com.example.common.fly;

import com.example.fly.dto.CreateMachineRequest;
import com.example.fly.dto.Machine;
import com.example.mqprocessor.config.FlyProperties;
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
    private final FlyProperties props;

    public FlyMachinesClient(WebClient flyWebClient, FlyProperties props) {
        this.wc = flyWebClient;
        this.props = props;
    }

    private Duration timeout() {
        return Duration.ofMillis(props.http().timeoutMs());
    }

    public Flux<Machine> listMachines() {
        return wc.get()
                .uri("/v1/apps/{app}/machines", props.appName())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToFlux(Machine.class)
                .timeout(timeout());
    }

    public Mono<Machine> getMachine(String machineId) {
        return wc.get()
                .uri("/v1/apps/{app}/machines/{id}", props.appName(), machineId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToMono(Machine.class)
                .timeout(timeout());
    }

    public Mono<Machine> createMachine(CreateMachineRequest req) {
        return wc.post()
                .uri("/v1/apps/{app}/machines", props.appName())
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
                .uri("/v1/apps/{app}/machines/{id}", props.appName(), machineId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToMono(Void.class)
                .timeout(timeout());
    }

    private Mono<Void> actionPostVoid(String pathTemplate, String machineId) {
        return wc.post()
                .uri(pathTemplate, props.appName(), machineId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFlyError)
                .bodyToMono(Void.class)
                .timeout(timeout());
    }

    private Mono<? extends Throwable> toFlyError(ClientResponse resp) {
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(new FlyApiException(resp.statusCode().value(), body)));
    }
}
