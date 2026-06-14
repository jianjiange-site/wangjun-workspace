package com.dating.user.service;

import org.junit.jupiter.api.Test;
import com.dating.user.grpc.v1.CompleteProfileRequest;
import com.dating.user.grpc.v1.Gender;
import com.dating.user.grpc.v1.NextAction;
import com.dating.user.grpc.v1.UserStatus;
import com.dating.user.grpc.v1.UserType;

import static org.assertj.core.api.Assertions.assertThat;

class UserCommandServiceTests {

    @Test
    void completeProfileSkeletonActivatesRealPersonUser() {
        UserCommandService service = new UserCommandService();
        CompleteProfileRequest request = CompleteProfileRequest.newBuilder()
                .setUserId("usr_01HXAMPLE")
                .setNickname("Alice")
                .setGender(Gender.GENDER_FEMALE)
                .setBirthDate("1998-06-13")
                .setAvatarMediaId("media_avatar_001")
                .setRace("Asian")
                .setUserType(UserType.USER_TYPE_REAL_PERSON)
                .build();

        var response = service.completeProfile(request);

        assertThat(response.getUserId()).isEqualTo("usr_01HXAMPLE");
        assertThat(response.getStatus()).isEqualTo(UserStatus.USER_STATUS_ACTIVE);
        assertThat(response.getNextAction()).isEqualTo(NextAction.NEXT_ACTION_ENTER_APP);
    }
}
