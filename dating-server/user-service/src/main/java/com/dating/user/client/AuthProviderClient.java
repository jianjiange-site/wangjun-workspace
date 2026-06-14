package com.dating.user.client;

public interface AuthProviderClient {

    // TODO: implement provider token verification in Gateway/BFF or Auth Adapter.
    String resolveProviderUserId(String provider, String providerToken);
}
