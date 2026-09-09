package com.example.lotterysystem.common.errorcode;

public interface ControllerErrorCodeConstants {
    // ------ 人员模块错误码 --------

    ErrorCode REGISTER_ERROR = new ErrorCode(100, "注册失败");
    ErrorCode LOGIN_ERROR = new ErrorCode(101, "登录失败");

    // ------ 奖品模块错误码 --------

    ErrorCode FIND_PRIZE_LIST_ERROR = new ErrorCode(200, "查询奖品列表失败");


    // ------ 活动模块错误码 --------

    ErrorCode CREATE_ACTIVITY_ERROR = new ErrorCode(300, "创建活动失败");
    ErrorCode FIND_ACTIVITY_LIST_ERROR = new ErrorCode(301, "查询活动列表失败");
    ErrorCode GET_ACTIVITY_DETAIL_ERROR = new ErrorCode(302, "查询活动详细信息失败");




    // ------ 抽奖错误码 --------
}
