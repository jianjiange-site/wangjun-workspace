package com.dating.user.service;

import org.springframework.stereotype.Service;
import com.dating.user.grpc.v1.BatchGetUsersRequest;
import com.dating.user.grpc.v1.BatchGetUsersResponse;
import com.dating.user.grpc.v1.GetUserProfileRequest;
import com.dating.user.grpc.v1.GetUserProfileResponse;
import com.dating.user.grpc.v1.GetUserRequest;
import com.dating.user.grpc.v1.GetUserResponse;
import com.dating.user.grpc.v1.Gender;
import com.dating.user.grpc.v1.UserProfile;
import com.dating.user.grpc.v1.UserStatus;
import com.dating.user.grpc.v1.UserSummary;
import com.dating.user.grpc.v1.UserType;

@Service
public class UserQueryService {

    public GetUserResponse getUser(GetUserRequest request) {
        // TODO: load user summary by business user_id from persistence after schema approval.
        return GetUserResponse.newBuilder()
                .setUser(userSummary(request.getUserId()))
                .build();
    }

    public GetUserProfileResponse getUserProfile(GetUserProfileRequest request) {
        // TODO: load full profile by business user_id from persistence after schema approval.
        return GetUserProfileResponse.newBuilder()
                .setProfile(UserProfile.newBuilder()
                        .setUserId(userIdOrSkeleton(request.getUserId()))
                        .setNickname("TODO")
                        .setGender(Gender.GENDER_UNSPECIFIED)
                        .setBirthDate("2000-01-01")
                        .setAvatarMediaId("media_todo")
                        .setRace("TODO")
                        .setCity("TODO")
                        .setBio("TODO")
                        .setOccupation("TODO")
                        .setEducation("TODO")
                        .setMbti("TODO")
                        .build())
                .build();
    }

    public BatchGetUsersResponse batchGetUsers(BatchGetUsersRequest request) {
        BatchGetUsersResponse.Builder response = BatchGetUsersResponse.newBuilder();
        for (String userId : request.getUserIdsList()) {
            response.addUsers(userSummary(userId));
        }
        return response.build();
    }

    private static UserSummary userSummary(String userId) {
        return UserSummary.newBuilder()
                .setUserId(userIdOrSkeleton(userId))
                .setStatus(UserStatus.USER_STATUS_ACTIVE)
                .setUserType(UserType.USER_TYPE_REAL_PERSON)
                .build();
    }

    private static String userIdOrSkeleton(String userId) {
        return userId == null || userId.isBlank() ? "usr_skeleton" : userId;
    }
}
