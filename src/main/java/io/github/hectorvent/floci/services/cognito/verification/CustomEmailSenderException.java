package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.core.common.AwsException;

/**
 * Why a pool's Custom Email Sender could not take a message, as the error AWS answers the request
 * that asked for it (measured, eu-west-1, 2026-10-16): {@code CodeDeliveryFailureException} when
 * the code cannot be sealed under the pool's KMS key, {@code UnexpectedLambdaException} when the
 * function cannot be invoked. The request fails with it whether or not it declares it, while what
 * it did besides sending stays done: a sign-up keeps its user, an attribute update its new value.
 */
public final class CustomEmailSenderException extends AwsException {

    CustomEmailSenderException(String errorCode, String message) {
        super(errorCode, message, 400);
    }
}
