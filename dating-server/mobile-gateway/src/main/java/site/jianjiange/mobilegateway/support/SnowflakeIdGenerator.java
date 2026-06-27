package site.jianjiange.mobilegateway.support;

import org.springframework.stereotype.Component;
import site.jianjiange.mobilegateway.config.IdGeneratorConfig;

/**
 * 单实例雪花 ID 生成器。
 */
@Component
public class SnowflakeIdGenerator {

    //时间戳部分
    private static final long EPOCH = 1704067200000L;
    //机器 ID位数
    private static final long WORKER_ID_BITS = 5L;
    //数据中心ID位数
    private static final long DATACENTER_ID_BITS = 5L;
    //序列号，占 12 位
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private final long datacenterId;

    private long sequence;
    private long lastTimestamp = -1L;

    /**
     * 创建雪花 ID 生成器。
     *
     * @param config 雪花 ID 配置
     */
    public SnowflakeIdGenerator(IdGeneratorConfig config) {
        this.workerId = config.getWorkerId();
        this.datacenterId = config.getDatacenterId();
        if (workerId < 0 || workerId > MAX_WORKER_ID
                || datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("Snowflake worker-id and datacenter-id must be between 0 and 31");
        }
    }

    /**
     * 生成下一个单实例唯一雪花 ID。
     *
     * @return 雪花 ID
     */
    public synchronized long nextId() {
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards");
        }
        //如果生成过快在同一毫秒按照从1到2^12生成序列号保证不同
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        //使用按位或的功能拼接，因为不同的数字落在了不同的位号上可以安全按位或
        return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 等待到下一毫秒，避免同毫秒序列号耗尽时重复。
     *
     * @param lastTimestamp 上一次使用的时间戳
     * @return 新的毫秒时间戳
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 获取当前系统毫秒时间。
     *
     * @return 当前毫秒时间戳
     */
    private long timeGen() {
        return System.currentTimeMillis();
    }
}
