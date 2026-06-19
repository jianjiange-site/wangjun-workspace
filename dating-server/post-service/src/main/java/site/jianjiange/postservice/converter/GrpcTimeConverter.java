package site.jianjiange.postservice.converter;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * gRPC 时间转换工具。
 */
public final class GrpcTimeConverter {

    private GrpcTimeConverter() {
    }

    /**
     * 将业务时间转换为 protobuf Timestamp。
     *
     * @param time 业务时间
     * @return protobuf 时间戳
     */
    public static Timestamp toTimestamp(OffsetDateTime time) {
        if (time == null) {
            return Timestamp.getDefaultInstance();
        }
        Instant instant = time.toInstant();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    /**
     * 将 protobuf Timestamp 转换为业务时间。
     *
     * @param timestamp protobuf 时间戳
     * @return UTC 业务时间
     */
    public static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null || Timestamp.getDefaultInstance().equals(timestamp)) {
            return null;
        }
        return OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()),
                ZoneOffset.UTC);
    }
}
