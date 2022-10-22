package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @Description TODO
 * @Author ygw
 * @Date 2022/10/18 9:13
 * @Version 1.0
 */

@Component
public class RedisIdWorker {
    /**
     * 开始的时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    public long nextId(String key){
        //1、生成时间戳
        LocalDateTime time = LocalDateTime.now();
        long nowTimestamp = time.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowTimestamp - BEGIN_TIMESTAMP;

        //2、生成序列号，由于给递增序列号设置为32位，需要在生成序列号时给出限制
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd");
        String date = time.format(timeFormatter);

        /**
         * 重点关注此处redis数据结构的设置，inc:deal:20221018 - value，也就是说一天允许生成2^32个订单
         */
        Long count = stringRedisTemplate.opsForValue().increment("inc:" + key + ":" + date);

        //3、合并生成id
        long id = (timestamp << 32) | count;
        return id;
    }

}
