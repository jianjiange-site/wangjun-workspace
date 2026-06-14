package com.dating.user.service;

import org.springframework.stereotype.Service;

@Service
public class RegistrationService {

    public void advanceAfterLivenessPass() {
        // TODO: move REAL_PERSON users from PENDING_LIVENESS to PENDING_PROFILE.
    }

    public void activateAfterProfileComplete() {
        // TODO: move users from PENDING_PROFILE to ACTIVE.
    }
}
