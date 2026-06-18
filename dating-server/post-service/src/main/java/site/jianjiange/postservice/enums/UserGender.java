package site.jianjiange.postservice.enums;

/**
 * 用户性别枚举，Feed 只推荐异性作者内容。
 */
public enum UserGender {

    MALE,
    FEMALE;

    /**
     * 计算当前用户对应的目标作者性别。
     *
     * @return 异性作者性别
     */
    public UserGender opposite() {
        return this == MALE ? FEMALE : MALE;
    }
}
