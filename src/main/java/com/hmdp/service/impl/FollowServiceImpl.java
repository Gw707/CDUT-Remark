package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result isFollow(Long userId) {
        UserDTO user = UserHolder.getUser();
        Follow follow = query().eq("user_id", userId).eq("follow_user_id", user.getId()).one();
        if(follow == null) return Result.ok(false);
        return Result.ok(true);
    }

    @Override
    public Result follow(Long userId, boolean status) {
        //关注一个人时先判断是否关注了
        //如果没有关注，创建关注的关系
        //如果关注了则取消关系
        UserDTO user = UserHolder.getUser();
        Follow follow;
        if(status){
            follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(user.getId());
            follow.setCreateTime(LocalDateTime.now());
            boolean isSuccess = save(follow);
            if(isSuccess){
                stringRedisTemplate.opsForSet().add("follows:" + user.getId(), userId.toString());
            }
            return Result.ok(true);
        }
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, user.getId());
        remove(queryWrapper);

        return Result.ok(false);
    }

    @Override
    @Transactional
    public Result getCommonFollow(Long userId) {
        Set<String> common = stringRedisTemplate.opsForSet().intersect("follows:" + userId.toString(), "follows:" + UserHolder.getUser().getId());
        if(common.size() == 0) return Result.ok(Collections.emptyList());
        List<Long> commonIds = common.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = commonIds.stream().map(commonId -> {
            User user = userService.query().eq("id", commonId).one();
            return user;
        }).collect(Collectors.toList());
        return Result.ok(users);
    }
}
