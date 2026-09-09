package com.example.lotterysystem.common.utils;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.json.JsonParseException;

import java.util.List;
import java.util.concurrent.Callable;

public class JacksonUtil {
    private JacksonUtil() {

    }

    /**
     * 单例
     */
    private final static ObjectMapper OBJECT_MAPPER;

    static {
        OBJECT_MAPPER = new ObjectMapper();
    }

    private static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    private static  <T> T tryParse(Callable<T> parser) {
        return tryParse(parser, JacksonException.class);
    }

    private static  <T> T tryParse(Callable<T> parser, Class<? extends Exception> check) {
        try {
            return parser.call();
        } catch (Exception var4) {
            if (check.isAssignableFrom(var4.getClass())) {
                throw new JsonParseException(var4);
            }

            throw new IllegalStateException(var4);
        }
    }

    /**
     * 序列化方法
     *
     * @param object
     * @return
     */
    public static String writeValueAsString(Object object) {
        return JacksonUtil.tryParse(() -> {
            return JacksonUtil.getObjectMapper().writeValueAsString(object);
        });
    }

    /**
     * 反序列化
     *
     * @param content
     * @param valueType
     * @return
     * @param <T>
     */
    public static <T> T readValue(String content, Class<T> valueType) {
        return JacksonUtil.tryParse(() -> {
            return JacksonUtil.getObjectMapper().readValue(content, valueType);
        });

    }

    /**
     * 反序列化 List
     *
     * @param content
     * @param paramClasses
     * @return
     * @param <T>
     */
    public static <T> T readListValue(String content, Class<?> paramClasses) {
        JavaType javaType = JacksonUtil.getObjectMapper().getTypeFactory()
                .constructParametricType(List.class, paramClasses);
        return JacksonUtil.tryParse(() -> {
            return JacksonUtil.getObjectMapper().readValue(content, javaType);
        });
    }

}
