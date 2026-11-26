package com.book.user.controller;

import com.book.user.security.UserPrincipal;
import com.book.user.service.OwnProfileService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/profile")
public class OwnProfileController {
    private final OwnProfileService profile;
    public OwnProfileController(OwnProfileService profile){this.profile=profile;}
    @GetMapping("/details") public OwnProfileService.Details details(@AuthenticationPrincipal UserPrincipal actor){return profile.details(actor);}
    @GetMapping("/permissions") public OwnProfileService.Permissions permissions(@AuthenticationPrincipal UserPrincipal actor){return profile.permissions(actor);}
}
