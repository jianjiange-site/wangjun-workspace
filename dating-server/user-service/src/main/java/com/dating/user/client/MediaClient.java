package com.dating.user.client;

public interface MediaClient {

    // TODO: implement avatar upload/existence check against MinIO-backed media service.
    boolean mediaExists(String mediaId);
}
