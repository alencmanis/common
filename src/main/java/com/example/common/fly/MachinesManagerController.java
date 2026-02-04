package com.example.common.fly;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fly/machines")
public class MachinesManagerController {

    private final MachinesManagerService svc;

    public MachinesManagerController(MachinesManagerService svc) {
        this.svc = svc;
    }

//    @PostMapping("/ensure/{name}")
//    public Mono<?> ensure(@PathVariable String name, @RequestBody @Valid ManagedMachineSpec spec) {
//        return svc.ensureMachine(name, spec);
//    }

//    @PostMapping("/scale")
//    public Mono<?> scale(
//            @RequestParam String role,
//            @RequestParam String version,
//            @RequestParam int desired,
//            @RequestBody @Valid ManagedMachineSpec spec
//    ) {
//        return svc.scaleTo(role, version, desired, spec);
//    }

//    @PostMapping("/rolling")
//    public Mono<?> rolling(
//            @RequestParam String role,
//            @RequestParam String fromVersion,
//            @RequestParam String toVersion,
//            @RequestParam int desired,
//            @RequestBody @Valid ManagedMachineSpec spec
//    ) {
//        return svc.rollingUpdate(role, fromVersion, toVersion, desired, spec);
//    }
}
