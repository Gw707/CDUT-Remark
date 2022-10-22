package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基于逻辑过期解决缓存击穿
 * 将过期时间和实际对象进行封装
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
