package com.example.lotterysystem.common.interceptor;

import com.example.lotterysystem.common.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.logging.Logger;

@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {


    /**
     * 预处理，业务请求之前调用
     *
     * @param request
     * @param response
     * @param handler
     * @return
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 获取请求头
        String token = request.getHeader("user_token");
        log.info("获取token：{}", token);
        log.info("获取路径：{}", request.getRequestURI());
        // 令牌解析
        Claims claims = JWTUtil.parseJWT(token);
        if (null == claims) {
            log.error("解析JWT令牌失败！");
            response.setStatus(401);
            return false;
        }

        log.info("解析JWT令牌成功！放行");
        return true;
    }

}
