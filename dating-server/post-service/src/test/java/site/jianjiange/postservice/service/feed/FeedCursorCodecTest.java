package site.jianjiange.postservice.service.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import site.jianjiange.postservice.enums.UserGender;
import site.jianjiange.postservice.exception.BusinessException;

class FeedCursorCodecTest {

    @Test
    void encodeAndDecodeSignedCursor() {
        FeedCursorCodec codec = new FeedCursorCodec("test-secret");
        FeedCursor cursor = new FeedCursor(
                1001L,
                UserGender.MALE,
                OffsetDateTime.now().plusMinutes(30),
                OffsetDateTime.now(),
                20);

        String encoded = codec.encode(cursor);

        FeedCursor decoded = codec.decode(encoded).orElseThrow();
        assertThat(decoded.userId()).isEqualTo(cursor.userId());
        assertThat(decoded.targetGender()).isEqualTo(cursor.targetGender());
        assertThat(decoded.expireAt().toInstant().toEpochMilli())
                .isEqualTo(cursor.expireAt().toInstant().toEpochMilli());
        assertThat(decoded.newBefore().toInstant().toEpochMilli())
                .isEqualTo(cursor.newBefore().toInstant().toEpochMilli());
        assertThat(decoded.hotOffset()).isEqualTo(cursor.hotOffset());
    }

    @Test
    void decodeRejectsTamperedCursor() {
        FeedCursorCodec codec = new FeedCursorCodec("test-secret");
        String encoded = codec.encode(new FeedCursor(
                1001L,
                UserGender.MALE,
                OffsetDateTime.now().plusMinutes(30),
                OffsetDateTime.now(),
                20));

        String tampered = encoded.substring(0, encoded.length() - 1) + "x";

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(BusinessException.class);
    }
}
