package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;


@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @GetMapping("/or/not/{userId}")
    public Result IsFollow(@PathVariable("userId") Long userId){
        return followService.isFollow(userId);
    }

    @PutMapping("/{userId}/{status}")
    public Result follow(@PathVariable("userId") Long userId, @PathVariable("status") boolean status){
        return followService.follow(userId, status);
    }

    @GetMapping("/common/{userId}")
    public Result getCommonFollow(@PathVariable("userId") Long userId){
        return followService.getCommonFollow(userId);
    }

}
