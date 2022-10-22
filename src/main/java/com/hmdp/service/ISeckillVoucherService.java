package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.extension.service.IService;

public interface ISeckillVoucherService extends IService<SeckillVoucher> {

    Result seckillVoucher(Long voucherId);

    Result createVoucherOrder(Long voucherId);

    public Result seckillVoucher2(Long voucherId);
}
