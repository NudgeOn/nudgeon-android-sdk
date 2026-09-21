package io.nudgeon.sdk

/** Recommended event names shared with the console. Pass to [NudgeOn.track].
 * Constants do not emit events or identify users; custom names remain supported.
 */
object NudgeOnEvents {
    const val SIGN_UP = "sign_up"
    const val LOGIN = "login"
    const val PURCHASE_COMPLETED = "purchase_completed"
    const val PRODUCT_VIEWED = "product_viewed"
    const val ADD_TO_CART = "add_to_cart"
    const val CHECKOUT_STARTED = "checkout_started"
}
