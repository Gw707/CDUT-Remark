package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result SendCode(String phone, HttpSession session) {
        //1、判断手机号是否有效
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("请输入正确的手机号");
        }

        //2、生成六位验证码
        String code = RandomUtil.randomNumbers(6);

        //3、将验证码放至redis中，并设置一个有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //4、将验证码发送至手机
        log.info("验证码为:{}", code);

        //5、返回ok
        return Result.ok("获取验证码成功");
    }

    @Override
    public Result UserLogin(LoginFormDTO loginForm, HttpSession session) {
        //1、从loginForm中获取手机号、验证码，校验手机号
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();

        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("请输入正确的手机号");
        }

        //2、从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        //3、进行比较，如不一样，返回验证码错误
        if(code == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }

        //4、如果一样则在数据库中查找该用户
        User user = query().eq("phone", phone).one();

        //5、如果没有查找到则创建新用户，并保存到数据库中
        if(user == null){
            //创建新用户，并保存到数据库中
            user = createUserWithPhone(phone);
        }

        //6、如果查找到则进行登录，将用户信息放至redis中
        //6.1、随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);

        //6.2、将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        //6.3、存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL,TimeUnit.MINUTES);

        //7、返回token
        return Result.ok(token);
    }

    @Override
    public Result userSign() {
        //1、获取当前登录用户
        UserDTO user = UserHolder.getUser();
        //2、获取日期
        LocalDateTime now = LocalDateTime.now();
        String today = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3、拼接key
        String key = USER_SIGN_KEY + user.getId() + today;
        //4、获取今天是本月第几天，即redis中的offset
        int dayOfMonth = now.getDayOfMonth();
        //5、写入redis   setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result getSign() {
        //1、获取当前登录用户
        UserDTO user = UserHolder.getUser();
        //2、获取日期
        LocalDateTime now = LocalDateTime.now();
        String today = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3、拼接key
        String key = USER_SIGN_KEY + user.getId() + today;
        //4、获取今天是本月第几天，即redis中的offset
        int dayOfMonth = now.getDayOfMonth();

        //5、获取本月到今天为止的所有签到情况，获得一个无符号整数
        List<Long> results = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        /**
         * 看业务统计的是连续签到天数还是一个月签到的总天数，
         * 如果是连续签到天数遍历到0直接退出循环即可
         * 在此处实现的是统计当月签到总天数
         */
        int count = 0;
        Long result = results.get(0);
        if(result == null || result == 0) return Result.ok(0);
        //6、循环遍历这个无符号数的每一位，位运算
        while(result != 0L){
            long bit = result & 1;
            //7、判断这一位是否为1
            //8、如果为1，签到天数count++
            if(bit == 1L) count++;
            result >>>= 1;
        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
