-- 1、参数列表
-- 1.1、优惠券id
local voucherId = ARGV[1]
-- 1.2、用户id
local userId = ARGV[2]
-- 1.3、订单id
local orderId = ARGV[3]
-- 2、数据key
-- 1.1、库存key
local stockKey = "seckill:stock:" .. voucherId
-- 1.2、订单key
local orderKey = "seckill:order:" .. voucherId
-- 3、脚本业务
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.1、判断库存是否充足，如果不足返回1
    return 1
end
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.2、判断用户是否下过单，如果下过单返回2
    return 2
end
-- 4、扣减库存
redis.call('incrby', stockKey, -1)
-- 5、将userId存入订单的set集合，返回0
redis.call('sadd',orderKey, userId)
-- 6、发送消息到消息队列中  xadd stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0