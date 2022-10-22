package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopService extends IService<Shop> {

    Result getByIdWithCache(Long id);

    Result updateByIdWithCache(Shop shop);

    Shop queryWithMutex(Long id);

    void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException;

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

}
