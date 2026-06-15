package site.jianjiange.postservice.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 图片状态枚举，对应数据库 post_image.status 字段。
 */
public enum ImageStatus {

    TEMP("TEMP"),
    BOUND("BOUND"),
    CLEANING("CLEANING"),
    CLEANED("CLEANED"),
    DELETE_FAILED("DELETE_FAILED");

    @EnumValue
    private final String value;

    /**
     * 创建图片状态枚举值。
     *
     * @param value 入库保存的状态值
     */
    ImageStatus(String value) {
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
