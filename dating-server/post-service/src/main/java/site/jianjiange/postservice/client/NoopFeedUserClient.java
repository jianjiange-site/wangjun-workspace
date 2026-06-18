package site.jianjiange.postservice.client;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.enums.UserGender;

/**
 * User/Profile gRPC 接入前的默认实现；无缓存时 Feed 会按空结果兜底。
 */
@Component
public class NoopFeedUserClient implements FeedUserClient {

    @Override
    public Optional<UserGender> findGender(Long userId) {
        return Optional.empty();
    }

    @Override
    public Map<Long, UserGender> findGenders(Collection<Long> userIds) {
        return Map.of();
    }

    @Override
    public List<Long> listLikedUserIds(Long userId) {
        return List.of();
    }
}
