package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String STREAM_ORDER_MQ = "stream.orders";
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //专门处理将订单信息写入数据库的线程
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //1、获取消息队列中的信息
                    //xreadgroup group g1 c1 count 1 block 2000 streams stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().block(Duration.ofSeconds(2)).count(1),
                            StreamOffset.create(STREAM_ORDER_MQ, ReadOffset.lastConsumed())
                    );

                    //2判断获取消息是否成功
                    if(records == null || records.isEmpty()){
                        //3、如果失败，说明没有消息，继续循环
                        continue;
                    }
                    //String 为消息的id, <Object, Object>存的是消息队列中的键值对
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> map = record.getValue();

                    //4、如果获取成功进行下单
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    handleCreateOrder(voucherOrder);

                    //5、在消息队列中对此条消息进行确认
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDER_MQ, "g1", record.getId());

                } catch (Exception e) {
                    log.error("订单处理错误", e);
                    //当出现异常时，从pendingList获取未处理的消息继续处理
                    handlePendingList();
                }
            }
        }

        private void handlePendingList(){
            while(true){
                try {
                    //1、获取pending-list中的异常消息
                    //xreadgroup group g1 c1 count 1 block 2000 streams stream.orders 0
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_ORDER_MQ, ReadOffset.from("0"))
                    );

                    //2判断是否有异常消息
                    if(records == null || records.isEmpty()){
                        //3、如果没有跳出循环，执行正常消息
                        break;
                    }
                    //String 为消息的id, <Object, Object>存的是消息队列中的键值对
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> map = record.getValue();

                    //4、如果获取成功进行下单
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    handleCreateOrder(voucherOrder);

                    //5、在消息队列中对此条消息进行确认
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDER_MQ, "g1", record.getId());

                } catch (Exception e) {
                    log.error("订单处理错误", e);
                    //如果又出现异常，进入下一轮循环
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

        private void handleCreateOrder(VoucherOrder voucherOrder) {
            voucherOrderService.save(voucherOrder);
        }
    }


    //静态加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    public Result seckillVoucher2(Long voucherId){
        UserDTO user = UserHolder.getUser();
        long orderId = redisIdWorker.nextId("order");

        //1、执行lua脚本
        Long returnValue = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                user.getId().toString(),
                String.valueOf(orderId)
        );
        //2、判断结果是否为0
        //3、不为0，没有资格购买
        int result = returnValue.intValue();
        if(result != 0){
            return Result.fail(result == 1 ? "库存不足" : "仅限抢购一单");
        }

        //4、如果有购买资格的话在lua脚本中就将订单加入到消息队列到中了


        return Result.ok(orderId);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = query().eq("voucher_id", voucherId).one();

        LocalDateTime now = LocalDateTime.now();

        //1、判断当前时间是否处在活动时间段内
        if (now.isBefore(voucher.getBeginTime()) || now.isAfter(voucher.getEndTime())) {
            //如果不再活动的时间段内，之间返回
            return Result.fail("请等待活动开放后重试");
        }

        //2、如果处在活动时间段内，对库存进行判断
        if(voucher.getStock() < 1){
            return Result.fail("优惠券已经抢完了");
        }

        //3、如果库存大于0，优惠券数量减一并创建订单
        Long userId = UserHolder.getUser().getId();

        //使用intern()是为了确保锁住的是toString后常量池中的值，而不是引用
        synchronized (userId.toString().intern()){
            /**
             * 关于事务失效的说明
             * 在spring中我们将SeckillVoucherServiceImpl交由proxy来进行代理
             * 也就是说SeckillVoucherServiceImpl中的事务实际上由proxy来完成
             * 直接调用createVoucherOrder()事务实际上调用的是SeckillVoucherServiceImpl.createVoucherOrder()
             * 会引起事务的失效，因此下面的操作是为了防止事务失效
             */
            //需要加上aspectjweaver依赖，并在启动程序上开启@EnableAspectJAutoProxy(exposeProxy = true)
            ISeckillVoucherService seckillVoucherService = (ISeckillVoucherService) AopContext.currentProxy();

            return seckillVoucherService.createVoucherOrder(voucherId);
        }
    }

    /**
     * 我所理解的此处的事务为：
     * 事务确保的要么都成功，要么都失败然后进行回滚->是对事务的完整性进行保证
     * 锁是锁住变量然后针对这个变量进行的一系列操作->是对并发安全进行保证
     * @param voucherId
     * @return
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        /**
         * TODO 需要判断该用户是否已经抢到了优惠券
         * 并发情况下，可能有多个线程同时进入查询，获得相同的数据，同时满足了更新条件
         * 因此我们在查询时，需要对用户的id进行加锁
         * 此种方法选用的是悲观锁，直接加synchronized即可
         */
        Integer count = voucherOrderService.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("优惠券每人限领一张");
        }

        boolean success = update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if(!success){
            return Result.fail("优惠券被抢完了");
        }

        //4、创建订单返回订单的id
        long orderId = redisIdWorker.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);

        voucherOrderService.save(voucherOrder);

        return Result.ok(orderId);
    }
}
