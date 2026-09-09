package com.example.lotterysystem.controller.result;

import lombok.Data;

import java.io.Serializable;

@Data
public class CreateActivityResult implements Serializable {

    /**
     * 创建的活动id
     */
    private Long activityId;

}
