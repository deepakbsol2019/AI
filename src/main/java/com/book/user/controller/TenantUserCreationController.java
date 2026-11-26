package com.book.user.controller;

import com.book.user.security.UserPrincipal;
import com.book.user.service.TenantUserCreationService;
import com.book.user.service.TenantUserCreationService.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/users")
public class TenantUserCreationController {
    private final TenantUserCreationService service;
    public TenantUserCreationController(TenantUserCreationService service){this.service=service;}
    @GetMapping("/capabilities") public Capabilities capabilities(@AuthenticationPrincipal UserPrincipal actor){return service.capabilities(actor);}
    @GetMapping("/create-options") public Options options(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false) Long tenantId){return service.options(actor,tenantId);}
    @GetMapping("/create-tenants") public java.util.List<Options> tenants(@AuthenticationPrincipal UserPrincipal actor){return service.tenants(actor);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Created create(@AuthenticationPrincipal UserPrincipal actor,@RequestBody Create input){return service.create(actor,input);}
}
