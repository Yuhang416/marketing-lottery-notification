package com.example.lotterysystem.service.dto;

import com.example.lotterysystem.service.enums.ActivityPrizeStatusEnum;
import com.example.lotterysystem.service.enums.ActivityPrizeTiersEnum;
import com.example.lotterysystem.service.enums.ActivityStatusEnum;
import com.example.lotterysystem.service.enums.ActivityUserStatusEnum;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ActivityDetailDTO {
    // 活动信息

    /**
     * 活动id
     */
    private Long activityId;

    /**
     * 活动名称
     */
    private String activityName;

    /**
     * 活动描述
     */
    private String desc;

    /**
     * 活动状态
     */
    private ActivityStatusEnum status;

    public Boolean valid() {
        return status.equals(ActivityStatusEnum.RUNNING);
    }

    // 奖品信息（列表）

    private List<PrizeDTO> prizeDTOList;

    // 人员信息（列表）

    private List<UserDTO> userDTOList;


    @Data
    public static class PrizeDTO {
        /**
         * 奖品Id
         */
        private Long prizeId;
        /**
         * 奖品名
         */
        private String name;

        /**
         * 图片索引
         */
        private String imageUrl;

        /**
         * 价格
         */
        private BigDecimal price;

        /**
         * 描述
         */
        private String description;

        /**
         * 奖品等奖
         */
        private ActivityPrizeTiersEnum tiers;

        /**
         * 奖品数量
         */
        private Long prizeAmount;

        /**
         * 奖品状态
         */
        private ActivityPrizeStatusEnum status;

        public Boolean valid() {
            return status.equals(ActivityPrizeStatusEnum.INIT);
        }
    }

    @Data
    public static class UserDTO {
        /**
         * 用户id
         */
        private Long userId;
        /**
         * 姓名
         */
        private String userName;
        /**
         * 状态
         */
        private ActivityUserStatusEnum status;

        public Boolean valid() {
            return status.equals(ActivityUserStatusEnum.INIT);
        }
    }

}
