package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.NonNull;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result getByIdWithCache(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1、根据id在redis中进行查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、如果查询到了，直接返回商铺的信息
        if(!StrUtil.isBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            //为防止缓存穿透在redis中增加了空值对象，需要判断是否为空
            if(shop == null) return Result.fail("不存在该商铺");
            return Result.ok(shop);
        }

        //3、如果没有查到，在数据库中进行查找
        Shop shop = query().eq("id", id).one();

        //4、数据如果为空，在redis中记录空值并返回
        if(shop == null){
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "null", CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.fail("无此店铺");
        }

        //5、数据不为空，将数据放至redis中，并设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);
    }


    @Override
    @Transactional
    public Result updateByIdWithCache(Shop shop) {
        //先判断id是否为空
        Long id = shop.getId();
        if(id == null) return Result.fail("该商铺不存在");

        //1、更新数据库
        updateById(shop);

        //2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok("更新成功");
    }

    /**
     * 在防止缓存穿透(缓存空对象)的基础上也实现了防止缓存击穿(互斥锁)
     * @param id
     * @return
     */
    @Override
    public Shop queryWithMutex(Long id) {

        String key = CACHE_SHOP_KEY + id;

        //1、根据id在redis中进行查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、如果查询到了，直接返回商铺的信息
        if(StrUtil.isNotBlank(shopJson)){
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            //为防止缓存穿透在redis中增加了空值对象，需要判断是否为空
//            if(shop == null) return Result.fail("不存在该商铺");
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        /**
         * ADD 如果未命中，则在此处进行缓存重建，在查询数据库前进行加锁
         */
        Shop shop = null;
        try {
            //TODO ADD1尝试获取互斥锁
            boolean hasLock = tryLock(LOCK_SHOP_KEY + id);
            //TODO 获取锁之后应该再查一遍redis中是否有数据，可能其他请求在使用锁时就已经将数据存到redis中了

            //TODO ADD2判断是否获取成功
            if(!hasLock){
                Thread.sleep(50);
                //TODO ADD3如果失败休眠后重试
                return queryWithMutex(id);  //此处用递归就是有一个刷新redis的效果，再次获取锁之前去redis中再查一遍
            }

            //TODO ADD4获取锁成功，进入数据库查询
            //3、如果没有查到，在数据库中进行查找
            shop = query().eq("id", id).one();
            Thread.sleep(500);  //模拟缓存重建时间很长的情况下

            //4、数据如果为空，将空对象放至redis中
            if(shop == null){
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "null", CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }

            //5、数据不为空，将数据放至redis中，并设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        }catch (InterruptedException e) {
            throw new RuntimeException();
        }finally {
            //TODO ADD5释放互斥锁
            unLock(LOCK_SHOP_KEY + id);
        }

        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


    @Override  //提前做数据预热，将热点店铺信息先存入redis中
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1、查询店铺数据
        Shop shop = query().eq("id", id).one();
        Thread.sleep(200);  //模拟数据库联表查询的耗时
        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3、写入Redis,不设过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1、判断是否需要根据坐标查询
        if(x == null || y == null){
            //不需要坐标进行查询，直接分页
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2、计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        String key = SHOP_GEO_KEY + typeId;
        //3、查询redis，按照举例进行排序、分页，结果以Map<shopId,distance>存储
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()  //用geoSearch进行查找指定中心点、查询半径、排序、记录条数等信息
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        //加上redis的一些命令参数，比如查找结果带有距离、分页等等
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //4、解析出id
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //4.1、截掉0~from的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>();
        list.stream().skip(from).forEach(result -> {
            //4.2、获取店铺id
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            //4.3、获取距离
            Distance distance = result.getDistance();

            //将id和distance匹配存放在map中
            distanceMap.put(shopId, distance);
        });
        //5、查询店铺信息
        //根据ids查询店铺数据
        if(ids == null || ids.size() == 0) return Result.ok();
        String strIds = StrUtil.join(",", ids);
        if(strIds == null) return Result.ok("没有更多了");
        List<Shop> shopList = query().in("id", ids).last("order by field(id, " + strIds + ")").list();
        //6、将距离信息放入店铺信息中返回
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shopList);
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;

        //1、根据id在redis中进行查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、如果未命中，直接返回空值
        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        //3、命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean(data, Shop.class);

        //4、判断逻辑逻辑时间是否过期
        if(LocalDateTime.now().isAfter(expireTime)){
            //5、未过期，直接返回商铺信息
            return shop;
        }

        String lockKey = LOCK_SHOP_KEY + id;

        //6、过期，尝试获取互斥锁
        boolean isLock = tryLock(lockKey);

        //7、如获取到互斥锁，开启一个新的线程，进行缓存重建
        //获取到锁后应进行DoubleCheck，再次查询Redis
        if(isLock){
            //重新查数据库并将新数据写到redis中
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //8、缓存重建
                    saveShop2Redis(id, 20L);
                }catch (Exception e){
                    throw new RuntimeException();
                }finally {
                    //8、释放锁
                    unLock(lockKey);
                }

            });
        }

        //9、返回redis中的旧数据
        return shop;
    }


}
