package com.example.lotterysystem.service.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum ActivityPrizeTiersEnum {

    FIRST_PRIZE(1, "一等奖"),

    SECOND_PRIZE(2, "二等奖"),

    THIRD_PRIZE(3, "三等奖");

    private final Integer code;

    private final String message;

    public static ActivityPrizeTiersEnum forName(String name) {
        for (ActivityPrizeTiersEnum activityPrizeTiersEnum : ActivityPrizeTiersEnum.values()) {
            if (activityPrizeTiersEnum.name().equalsIgnoreCase(name)) {
                return activityPrizeTiersEnum;
            }
        }
        return null;
    }

}
