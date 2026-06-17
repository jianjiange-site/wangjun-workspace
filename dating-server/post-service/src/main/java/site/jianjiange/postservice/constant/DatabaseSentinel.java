package site.jianjiange.postservice.constant;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 数据库非空字段的哨兵值，避免用 NULL 表达“暂无关联/未发生”。
 */
public final class DatabaseSentinel {

    public static final long NONE_ID = -1L;
    public static final int NONE_NUMBER = -1;
    public static final String NONE_TEXT = "";
    public static final OffsetDateTime NONE_TIME =
            OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private DatabaseSentinel() {
    }

    /**
     * 判断业务 ID 是否为“无关联”哨兵。
     *
     * @param value 待判断 ID
     * @return 为空或哨兵值时返回 true
     */
    public static boolean isNoneId(Long value) {
        return value == null || value == NONE_ID;
    }
}
