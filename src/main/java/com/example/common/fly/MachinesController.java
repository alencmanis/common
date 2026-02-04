package com.example.common.fly;

import com.example.common.fly.dto.CreateMachineRequest;
import com.example.common.fly.dto.Machine;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/machines")
public class MachinesController {

    private final FlyMachinesClient client;
    private final MachinesService service;

    public MachinesController(FlyMachinesClient client, MachinesService service) {
        this.client = client;
        this.service = service;
    }

    @GetMapping
    public Flux<Machine> list() {
        return client.listMachines();
    }

    @GetMapping("/{id}")
    public Mono<Machine> get(@PathVariable String id) {
        return client.getMachine(id);
    }

    @PostMapping
    public Mono<Machine> create(@RequestBody @Valid CreateMachineRequest req) {
        return client.createMachine(req);
    }

    @PostMapping("/{id}/start")
    public Mono<Void> start(@PathVariable String id) {
        return client.startMachine(id);
    }

    @PostMapping("/{id}/stop")
    public Mono<Void> stop(@PathVariable String id) {
        return client.stopMachine(id);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> destroy(@PathVariable String id) {
        return client.destroyMachine(id);
    }

    @PostMapping("/create-and-start")
    public Mono<Machine> createAndStart(@RequestBody @Valid CreateMachineRequest req) {
        return service.createAndStart(req);
    }
}
