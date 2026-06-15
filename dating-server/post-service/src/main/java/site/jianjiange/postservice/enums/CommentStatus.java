package site.jianjiange.postservice.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 评论状态枚举，对应数据库 comment.status 字段。
 */
public enum CommentStatus {

    NORMAL("NORMAL"),
    USER_DELETED("USER_DELETED");

    @EnumValue
    private final String value;

    /**
     * 创建评论状态枚举值。
     *
     * @param value 入库保存的状态值
     */
    CommentStatus(String value) {
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
