package com.example.lotterysystem.dao.mapper;

import com.example.lotterysystem.dao.dataobject.DeadLetterRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface DeadLetterRecordMapper {

    @Insert("insert into dead_letter_record (source_queue, source_exchange, source_routing_key, message_body," +
            " reason, death_count, first_death_time, status)" +
            " values (#{sourceQueue}, #{sourceExchange}, #{sourceRoutingKey}, #{messageBody}," +
            " #{reason}, #{deathCount}, #{firstDeathTime}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(DeadLetterRecordDO record);

    @Select("select * from dead_letter_record where id = #{id}")
    DeadLetterRecordDO selectById(@Param("id") Long id);

    /** 只有 PENDING 的记录能改成已重放，同一条记录被重复点重放时只有一次生效。 */
    @Update("update dead_letter_record set status = 'REPLAYED', replay_time = #{replayTime}" +
            " where id = #{id} and status = 'PENDING'")
    int markReplayed(@Param("id") Long id, @Param("replayTime") Date replayTime);

    /** 重放发送失败时退回待处理。 */
    @Update("update dead_letter_record set status = 'PENDING', replay_time = null" +
            " where id = #{id} and status = 'REPLAYED'")
    int revertReplayed(@Param("id") Long id);
}
