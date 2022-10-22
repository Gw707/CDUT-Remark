package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * @Description TODO
 * @Author ygw
 * @Date 2022/10/15 15:37
 * @Version 1.0
 */
public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1、从redis获取请求头中的token
        String token = request.getHeader("authorization");

        //2、根据token获取redis中的用户信息，
        if(StrUtil.isBlank(token)){
            //token为空，进行拦截
            response.setStatus(401);
            return false;
        }

        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);


        //3、判断用户是否存在，不存在时对请求进行拦截
        if(userMap == null){
            //4、不存在拦截，返回401代码
            response.setStatus(401);
            return true;
        }
        //5、将查询到的Hash数据转成UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        if(userDTO == null){
            //4、不存在拦截，返回401代码
            response.setStatus(401);
            return true;
        }
        //6、用户存在时，将用户信息保存到ThreadLocal中
        UserHolder.saveUser(userDTO);

        //7、刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //当前请求执行完毕后，需要移除用户信息，防止内存泄漏
        UserHolder.removeUser();
    }
}
