package com.prism.tva.core

/**
 * Safety boundary. The assistant never presses a payment button and never acts on a login, OTP or
 * password screen: it stops and hands control back to the user.
 */
object Guard {

    /** Buttons that would start or commit a payment (matched on normalised, space-padded labels). */
    private val PAY_ACTIONS = listOf(
        " pay ", " pay now", " place order", " proceed to buy", " proceed to checkout", " proceed to pay",
        " add payment method", " make payment", " confirm order", " confirm and pay", " buy now",
        " complete payment", " slide to pay", " swipe to pay", " pay using", " checkout ",
    )

    /** Words that, two or more together, mark a payment-method screen. */
    private val PAY_SCREEN = listOf(
        "upi", "net banking", "netbanking", "credit card", "debit card", "select payment", "payment method",
        "payment options", "cvv", "card number", "wallets", "cash on delivery", "pay on delivery", "emi",
    )

    /** Hints or labels of text fields that ask for credentials. */
    private val CRED_FIELDS = listOf(
        "otp", "one time password", "verification code", "enter code", "password", "passcode", "pin",
        "phone number", "mobile number", "email", "e mail", "username",
    )
    private val AUTH_WORDS = listOf(
        "log in", "login", "sign in", "signin", "sign up", "verify", "continue with", "otp", "welcome back",
    )

    private fun padded(s: String) = " " + Text.norm(s) + " "

    /** Non-null reason if tapping an element with these labels could start a payment. */
    fun actionBlock(labels: Collection<String>): String? {
        for (l in labels) {
            val n = padded(l)
            if (PAY_ACTIONS.any { n.contains(it) }) return "payment"
        }
        return null
    }

    /** Non-null reason if the current app screen is a payment, login, OTP or password screen. */
    fun screenBlock(snap: Snapshot, pkg: String): String? {
        val nodes = snap.appNodes(pkg)
        if (nodes.any { it.password }) return "password"
        val screenText = padded(nodes.joinToString(" ") { it.label })
        val payHits = PAY_SCREEN.count { screenText.contains(" $it ") }
        if (payHits >= 2) return "payment"
        val credField = nodes.any { n ->
            n.editable && CRED_FIELDS.any { k -> padded(n.hint ?: "").contains(" $k ") || padded(n.label).contains(" $k ") }
        }
        if (credField && AUTH_WORDS.any { screenText.contains(" $it ") }) return "login"
        return null
    }

    fun spoken(reason: String) = when (reason) {
        "payment" -> "the payment step"
        "password" -> "a password screen"
        "login" -> "a login or OTP screen"
        else -> "a sensitive screen"
    }
}
