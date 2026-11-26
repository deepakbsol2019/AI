package com.book.user.controller;
import java.util.List;
import com.book.user.security.UserPrincipal;
import com.book.user.service.*;
import com.book.user.service.UserManagementService.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api/user")
public class UserManagementController {
    private final UserManagementService users;private final UserDeletionService deletion;
    public UserManagementController(UserManagementService users,UserDeletionService deletion){this.users=users;this.deletion=deletion;}
    @GetMapping("/profile") public Profile current(@AuthenticationPrincipal UserPrincipal actor){return users.current(actor);}
    @GetMapping("/users") public Page list(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false) String search,@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(required=false) Long tenantId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return users.list(actor,search,status,tenantId,page,size);}
    @GetMapping("/users/{id}") public Details details(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){return users.details(actor,id);}
    @PutMapping("/users/{id}") public Details edit(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,@RequestBody Edit input){return users.edit(actor,id,input);}
    @GetMapping("/users/{id}/deletion-preview") public Preview preview(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,@RequestParam boolean hard){return users.preview(actor,id,hard);}
    @PostMapping("/users/{id}/soft-delete") @ResponseStatus(HttpStatus.NO_CONTENT) public void soft(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,@RequestBody Confirm input){users.softDelete(actor,id,input);}
    @PostMapping("/users/{id}/hard-delete") @ResponseStatus(HttpStatus.ACCEPTED) public UserDeletionService.Job hard(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,@RequestBody Confirm input){return deletion.request(actor,id,input);}
    @PostMapping("/users/{id}/activate") public Details activate(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,@RequestBody Activate input){return users.activate(actor,id,input);}
    @GetMapping("/users/{id}/assignable-roles") public List<Role> assignable(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){return users.assignable(actor,id);}
    @PutMapping("/users/{id}/roles") public Details roles(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,@RequestBody Assign input){return users.assign(actor,id,input);}
    @GetMapping("/users/jobs/{id}") public UserDeletionService.Job job(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){return deletion.get(actor,id);}
    @GetMapping("/users/jobs") public List<UserDeletionService.Job> jobs(@AuthenticationPrincipal UserPrincipal actor){return deletion.list(actor);}
    @PostMapping("/users/jobs/{id}/retry") public UserDeletionService.Job retry(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){return deletion.retry(actor,id);}
}
