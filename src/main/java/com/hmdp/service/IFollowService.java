package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IFollowService extends IService<Follow> {

    Result isFollow(Long userId);

    Result follow(Long userId, boolean status);

    Result getCommonFollow(Long userId);
}
