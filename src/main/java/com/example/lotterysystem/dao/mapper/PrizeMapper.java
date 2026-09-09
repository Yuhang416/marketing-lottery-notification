package com.example.lotterysystem.dao.mapper;

import com.example.lotterysystem.dao.dataobject.PrizeDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PrizeMapper {

    @Insert("insert into prize (name, image_url, price, description)" +
            " values (#{name}, #{imageUrl}, #{price}, #{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(PrizeDO prizeDO);

    @Select("select count(1) from prize")
    int count();

    @Select("select * from prize order by id desc limit #{offset}, #{pageSize}")
    List<PrizeDO> selectPrizeList(@Param("offset") Integer offset,
                                  @Param("pageSize") Integer pageSize);

    @Select("<script>" +
            " select id from prize" +
            " where id in" +
            " <foreach item='item' collection='items' open='(' separator=',' close=')'>" +
            " #{item}" +
            " </foreach>" +
            " </script>")
    List<Long> selectExistByIds(@Param("items") List<Long> ids);

    @Select("<script>" +
            " select * from prize" +
            " where id in" +
            " <foreach item='item' collection='items' open='(' separator=',' close=')'>" +
            " #{item}" +
            " </foreach>" +
            " </script>")
    List<PrizeDO> batchSelectByIds(@Param("items") List<Long> ids);

    @Select("select * from prize where id = #{id}")
    PrizeDO selectById(@Param("id") Long id);
}
