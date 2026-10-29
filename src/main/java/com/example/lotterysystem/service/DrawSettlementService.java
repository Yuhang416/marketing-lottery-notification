package com.example.lotterysystem.service;

import com.example.lotterysystem.controller.param.DrawPrizeParam;

public interface DrawSettlementService {

    /**
     * 开奖结算：抢占奖品、状态流转、写中奖记录和通知任务，在同一个本地事务里完成。
     *
     * @param param
     * @return true 表示本次完成了结算；false 表示这个奖已经开过，什么都没做
     */
    boolean settle(DrawPrizeParam param);
}
