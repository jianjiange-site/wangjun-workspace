package site.jianjiange.postservice.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 帖子状态枚举，对应数据库 post.status 字段。
 */
public enum PostStatus {

    PUBLISHED("PUBLISHED"),
    USER_DELETED("USER_DELETED"),
    AUDIT_REJECTED("AUDIT_REJECTED");

    @EnumValue
    private final String value;

    /**
     * 创建帖子状态枚举值。
     *
     * @param value 入库保存的状态值
     */
    PostStatus(String value) {
        this.value = value;
    }

    /**
     * 获取入库保存的状态值。
     *
     * @return 状态字符串
     */
    public String getValue() {
        return value;
    }
}
