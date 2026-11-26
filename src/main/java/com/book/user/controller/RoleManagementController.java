package com.book.user.controller;

import java.util.List;
import com.book.user.security.UserPrincipal;
import com.book.user.service.RoleManagementService;
import com.book.user.service.RoleManagementService.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/user/roles")
public class RoleManagementController {
    private final RoleManagementService roles;
    public RoleManagementController(RoleManagementService roles){this.roles=roles;}
    @GetMapping public List<Role> list(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false) Long tenantId){return roles.list(actor,tenantId);}
    @GetMapping("/catalog") public Catalog catalog(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false) Long tenantId){return roles.catalog(actor,tenantId);}
    @GetMapping("/organizations") public List<Organization> organizations(@AuthenticationPrincipal UserPrincipal actor){return roles.organizations(actor);}
    @GetMapping("/{id}") public Role get(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false) Long tenantId,@PathVariable long id){return roles.get(actor,tenantId,id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Role create(@AuthenticationPrincipal UserPrincipal actor,@RequestBody Write input){return roles.create(actor,input);}
    @PutMapping("/{id}") public Role update(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,@RequestBody Write input){return roles.update(actor,id,input);}
    @PutMapping("/{id}/members/{membershipId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false) Long tenantId,@PathVariable long id,@PathVariable long membershipId){roles.assign(actor,tenantId,id,membershipId,false);}
    @DeleteMapping("/{id}/members/{membershipId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false) Long tenantId,@PathVariable long id,@PathVariable long membershipId){roles.assign(actor,tenantId,id,membershipId,true);}
}
