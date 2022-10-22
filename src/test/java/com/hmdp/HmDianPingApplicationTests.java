package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;
    
    @Resource
    private IShopService shopService;
    
    @Test
    public void test() throws InterruptedException {

        System.out.println(redisIdWorker.nextId("deal"));
    }

    @Test
    public void loadShopGeoData(){
        //1、查询店铺信息
        List<Shop> shopList = shopService.list();
        //2、将店铺信息按照typeId进行分组
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3、分类型写入redis
        Set<Map.Entry<Long, List<Shop>>> entries = map.entrySet();
        for (Map.Entry<Long, List<Shop>> entry : entries) {
            //3.1、获取类型的id，拼接成key
            String key = SHOP_GEO_KEY + entry.getKey();
            //3.2、获取同类型的店铺集合
            List<Shop> shops = entry.getValue();
            //3.3、使用geoAdd写入redis中
            for (Shop shop : shops) {
                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
            }

        }



    }
}
