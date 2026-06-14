package com.dating.user.client;

public interface LivenessClient {

    // TODO: implement through Liveness-Service or third-party liveness provider.
    LivenessCheckResult getResult(String providerTraceId);

    record LivenessCheckResult(
            String provider,
            String providerTraceId,
            String result,
            double score,
            String failureReason
    ) {
    }
}
