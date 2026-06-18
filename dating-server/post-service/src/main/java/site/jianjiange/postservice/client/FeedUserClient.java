package site.jianjiange.postservice.client;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import site.jianjiange.postservice.enums.UserGender;

/**
 * Feed 依赖的用户侧端口，后续由 User/Profile gRPC client 实现。
 */
public interface FeedUserClient {

    /**
     * 查询单个用户性别。
     *
     * @param userId 用户 ID
     * @return 用户性别；不可用或未命中时返回空
     */
    Optional<UserGender> findGender(Long userId);

    /**
     * 批量查询用户性别。
     *
     * @param userIds 用户 ID 集合
     * @return 查询成功的用户性别映射
     */
    Map<Long, UserGender> findGenders(Collection<Long> userIds);

    /**
     * 查询当前用户喜欢过的人。
     *
     * @param userId 用户 ID
     * @return 喜欢过的用户 ID 列表
     */
    List<Long> listLikedUserIds(Long userId);
}
